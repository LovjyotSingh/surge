#!/bin/bash
# One-shot setup run by the es-init container on every `compose up` (idempotent), security audit SEC-05:
# a role limited to the "events" index and a "catalog" user that has only that role. catalog-service connects as
# that user; the "elastic" superuser password (ELASTIC_PASSWORD) is used only here.
#
# Role surge_catalog: cluster "monitor" (Spring Boot's health check reads _cluster/health) and, on "events" only,
# create_index, view_index_metadata, read, write and maintenance (refresh). It cannot delete the index or touch any
# other index.
set -euo pipefail

for name in ELASTIC_PASSWORD ELASTICSEARCH_CATALOG_PASSWORD; do
    if [[ ! ${!name:-} =~ ^[A-Za-z0-9._~-]{16,}$ ]]; then
        echo "$name must be at least 16 URL-safe characters (run docker/gen-secrets.sh)" >&2
        exit 1
    fi
done

umask 077
es=http://elasticsearch:9200
# curl reads the credentials from a config file, so they are never on a command line.
printf 'user = "elastic:%s"\n' "$ELASTIC_PASSWORD" > /tmp/curl-elastic.cfg

echo "es-init: waiting for Elasticsearch"
for attempt in $(seq 1 90); do
    status=$(curl -s -K /tmp/curl-elastic.cfg -o /dev/null -w '%{http_code}' \
        "$es/_cluster/health?wait_for_status=yellow&timeout=5s" || true)
    if [ "$status" = 200 ]; then
        break
    fi
    if [ "$attempt" -eq 90 ]; then
        echo "es-init: Elasticsearch did not become ready (last HTTP status: $status)" >&2
        exit 1
    fi
    sleep 2
done

role='{"cluster":["monitor"],"indices":[{"names":["events"],"privileges":["create_index","view_index_metadata","read","write","maintenance"]}]}'
printf '%s' "$role" | curl -fsS -K /tmp/curl-elastic.cfg -X PUT "$es/_security/role/surge_catalog" \
    -H 'Content-Type: application/json' --data-binary @- > /dev/null
echo "es-init: role surge_catalog in place"

printf '{"password":"%s","roles":["surge_catalog"],"full_name":"catalog-service"}' \
    "$ELASTICSEARCH_CATALOG_PASSWORD" \
    | curl -fsS -K /tmp/curl-elastic.cfg -X PUT "$es/_security/user/catalog" \
        -H 'Content-Type: application/json' --data-binary @- > /dev/null
echo "es-init: user catalog in place"
rm -f /tmp/curl-elastic.cfg
