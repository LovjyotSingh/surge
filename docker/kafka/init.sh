#!/bin/bash
# One-shot setup run by the kafka-init container once the broker is up, on every `compose up` (idempotent):
#   1. SCRAM-SHA-512 credentials for booking, catalog and notification (created or updated, so a new .env applies);
#   2. the topics with their partition counts (auto-creation is off on the broker);
#   3. the ACLs (security audit SEC-06, Task F):
#        booking       writes orders.v1 and catalog.v1.DLT; reads catalog.v1 as group booking-catalog
#        catalog       writes catalog.v1 and orders.v1.catalog.DLT; reads orders.v1 as group catalog-orders
#        notification  writes orders.v1.audit.DLT; reads orders.v1 as group notification-audit
#      No one else can produce a forged EventScheduled to catalog.v1, or OrderConfirmed/SalesReset to orders.v1.
# The services start only after this container exits 0 (depends_on: service_completed_successfully).
set -euo pipefail

# S10: each password comes from its compose secret file, /run/secrets/env/<NAME> (a variable of the same name, if set,
# wins), into a shell variable that is never exported.
for name in KAFKA_ADMIN_PASSWORD KAFKA_BOOKING_PASSWORD KAFKA_CATALOG_PASSWORD KAFKA_NOTIFICATION_PASSWORD; do
    secret_file=/run/secrets/env/$name
    if [[ -z ${!name:-} && -r $secret_file ]]; then
        value=$(< "$secret_file")
        printf -v "$name" '%s' "${value%$'\r'}"
    fi
    if [[ ! ${!name:-} =~ ^[A-Za-z0-9._~-]{16,}$ ]]; then
        echo "$name must be at least 16 URL-safe characters (run docker/gen-secrets.sh)" >&2
        exit 1
    fi
done

umask 077
bin=/opt/kafka/bin
server=kafka:9092
admin=/tmp/admin.properties
export LOG_DIR=/tmp/kafka-logs
export KAFKA_LOG4J_OPTS="-Dlog4j2.configurationFile=/etc/kafka/docker/tools-log4j2.yaml"
export KAFKA_HEAP_OPTS="-Xmx192m"
cat > "$admin" <<PROPS
security.protocol=SASL_PLAINTEXT
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="${KAFKA_ADMIN_PASSWORD}";
PROPS

echo "kafka-init: waiting for the broker"
for attempt in $(seq 1 60); do
    if "$bin/kafka-topics.sh" --bootstrap-server "$server" --command-config "$admin" --list > /dev/null 2>&1; then
        break
    fi
    if [ "$attempt" -eq 60 ]; then
        echo "kafka-init: the broker did not answer as admin within 2 minutes" >&2
        exit 1
    fi
    sleep 2
done

scram_user() {
    local user=$1 password=$2 out
    # The password is on this one-shot container's command line, which holds it in its environment anyway. The tool
    # echoes it in some errors, so its output is masked before it reaches the log.
    if ! out=$("$bin/kafka-configs.sh" --bootstrap-server "$server" --command-config "$admin" --alter \
            --entity-type users --entity-name "$user" \
            --add-config "SCRAM-SHA-512=[iterations=8192,password=${password}]" 2>&1); then
        echo "kafka-init: could not set the SCRAM credentials for $user:" >&2
        printf '%s\n' "${out//$password/****}" >&2
        exit 1
    fi
    echo "kafka-init: SCRAM credentials set for $user"
}
scram_user booking "$KAFKA_BOOKING_PASSWORD"
scram_user catalog "$KAFKA_CATALOG_PASSWORD"
scram_user notification "$KAFKA_NOTIFICATION_PASSWORD"

topic() {
    "$bin/kafka-topics.sh" --bootstrap-server "$server" --command-config "$admin" --create --if-not-exists \
        --topic "$1" --partitions "$2" --replication-factor 1 > /dev/null
    echo "kafka-init: topic $1 ($2 partitions)"
}
# The same names and partition counts as the services' NewTopic beans (key = eventId on the two main topics).
topic orders.v1 3
topic catalog.v1 3
topic catalog.v1.DLT 1
topic orders.v1.catalog.DLT 1
topic orders.v1.audit.DLT 1

acl() {
    "$bin/kafka-acls.sh" --bootstrap-server "$server" --command-config "$admin" --add "$@" > /dev/null
}
# Write implies Describe; Read on a topic or a group implies Describe too.
acl --allow-principal User:booking --operation Write --topic orders.v1
acl --allow-principal User:booking --operation Write --topic catalog.v1.DLT
acl --allow-principal User:booking --operation Read --topic catalog.v1
acl --allow-principal User:booking --operation Read --group booking-catalog
acl --allow-principal User:catalog --operation Write --topic catalog.v1
acl --allow-principal User:catalog --operation Write --topic orders.v1.catalog.DLT
acl --allow-principal User:catalog --allow-principal User:notification --operation Read --topic orders.v1
acl --allow-principal User:catalog --operation Read --group catalog-orders
acl --allow-principal User:notification --operation Write --topic orders.v1.audit.DLT
acl --allow-principal User:notification --operation Read --group notification-audit
echo "kafka-init: ACLs in place:"
"$bin/kafka-acls.sh" --bootstrap-server "$server" --command-config "$admin" --list
