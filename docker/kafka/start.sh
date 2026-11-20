#!/bin/bash
# Starts the single-node KRaft broker + controller with authentication and ACLs (security audit SEC-06).
# Mounted read-only into the kafka container and run instead of the image's /etc/kafka/docker/run, which writes
# into /opt/kafka/config (read-only here).
#
# Listeners:
#   SASL        :9092           clients and the broker itself, SASL_PLAINTEXT. SCRAM-SHA-512 for the services
#                               (users created by docker/kafka/init.sh); PLAIN for the "admin" super user only.
#   CONTROLLER  127.0.0.1:9093  KRaft controller, reachable inside this container only, SASL PLAIN as "admin".
# "admin" is a static PLAIN user (its password is in the JAAS lines below, never in the metadata log), so a new
# KAFKA_ADMIN_PASSWORD takes effect at the next start. SCRAM cannot be used on the controller listener.
#
# StandardAuthorizer with allow.everyone.if.no.acl.found=false: a user may do only what an ACL allows.
# auto.create.topics.enable=false: topics exist only because init.sh created them, with their partition counts.
set -euo pipefail

: "${KAFKA_CLUSTER_ID:?KAFKA_CLUSTER_ID is required}"
if [[ ! ${KAFKA_ADMIN_PASSWORD:-} =~ ^[A-Za-z0-9._~-]{16,}$ ]]; then
    echo "KAFKA_ADMIN_PASSWORD must be at least 16 URL-safe characters (run docker/gen-secrets.sh)" >&2
    exit 1
fi

umask 077
props=/tmp/server.properties
plain_jaas="org.apache.kafka.common.security.plain.PlainLoginModule required username=\"admin\" password=\"${KAFKA_ADMIN_PASSWORD}\" user_admin=\"${KAFKA_ADMIN_PASSWORD}\";"
cat > "$props" <<PROPS
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@127.0.0.1:9093
controller.listener.names=CONTROLLER
listeners=SASL://:9092,CONTROLLER://127.0.0.1:9093
advertised.listeners=SASL://kafka:9092
inter.broker.listener.name=SASL
listener.security.protocol.map=SASL:SASL_PLAINTEXT,CONTROLLER:SASL_PLAINTEXT
sasl.mechanism.inter.broker.protocol=PLAIN
sasl.mechanism.controller.protocol=PLAIN
listener.name.sasl.sasl.enabled.mechanisms=SCRAM-SHA-512,PLAIN
listener.name.sasl.plain.sasl.jaas.config=${plain_jaas}
listener.name.sasl.scram-sha-512.sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required;
listener.name.controller.sasl.enabled.mechanisms=PLAIN
listener.name.controller.plain.sasl.jaas.config=${plain_jaas}
authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer
allow.everyone.if.no.acl.found=false
super.users=User:admin
auto.create.topics.enable=false
log.dirs=/var/lib/kafka/data
num.partitions=3
default.replication.factor=1
min.insync.replicas=1
offsets.topic.replication.factor=1
offsets.topic.num.partitions=6
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
share.coordinator.state.topic.replication.factor=1
share.coordinator.state.topic.min.isr=1
group.initial.rebalance.delay.ms=0
PROPS

# Console logging only (the root file system is read-only).
export LOG_DIR=/tmp/kafka-logs
export KAFKA_LOG4J_OPTS="-Dlog4j2.configurationFile=/etc/kafka/docker/tools-log4j2.yaml"
/opt/kafka/bin/kafka-storage.sh format --cluster-id "$KAFKA_CLUSTER_ID" --config "$props" --ignore-formatted

export KAFKA_LOG4J_OPTS="-Dlog4j2.configurationFile=/etc/kafka/docker/log4j2.yaml"
export KAFKA_JVM_PERFORMANCE_OPTS="${KAFKA_JVM_PERFORMANCE_OPTS:-} -XX:SharedArchiveFile=/opt/kafka/kafka.jsa"
# No GC log: kafka-server-start.sh's default EXTRA_ARGS ("-name kafkaServer -loggc") makes kafka-run-class.sh write
# rotating GC logs (up to 10 x 100 MB) into LOG_DIR, i.e. onto the 64 MB /tmp tmpfs.
export EXTRA_ARGS="-name kafkaServer"
exec /opt/kafka/bin/kafka-server-start.sh "$props"
