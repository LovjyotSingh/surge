#!/bin/bash
# One MySQL user per service, with every privilege on its own database and none anywhere else (security audit
# SEC-05): booking's credentials cannot read auth.users. Passwords come from the environment (docker/gen-secrets.sh
# writes them to .env; compose passes them to the mysql container only).
#
# Run once by the mysql image's entrypoint (docker-entrypoint-initdb.d) on an EMPTY data directory, after
# 01-databases.sql. The entrypoint may source this file instead of executing it, so it neither calls exit nor
# changes shell options; a failing function stops the entrypoint (which runs with set -e).
#
# Rotating a service password later: ALTER USER '<user>'@'%' IDENTIFIED BY '<new>'; as root, then update .env.

surge_create_user() {
    local user=$1 database=$2 password=$3
    # Hex or base64url from gen-secrets.sh. Checked so the value can never break out of the SQL string below.
    if [[ ! $password =~ ^[A-Za-z0-9._~-]{16,}$ ]]; then
        echo "02-users.sh: the password for MySQL user '$user' is missing, shorter than 16 characters or not" \
             "URL-safe; run docker/gen-secrets.sh" >&2
        return 1
    fi
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot <<SQL
CREATE USER IF NOT EXISTS '${user}'@'%' IDENTIFIED BY '${password}';
GRANT ALL PRIVILEGES ON \`${database}\`.* TO '${user}'@'%';
SQL
    echo "02-users.sh: created MySQL user '$user' for database '$database'"
}

surge_create_user auth auth "${MYSQL_AUTH_PASSWORD:-}"
surge_create_user booking booking "${MYSQL_BOOKING_PASSWORD:-}"
surge_create_user catalog catalog "${MYSQL_CATALOG_PASSWORD:-}"
surge_create_user notification notification "${MYSQL_NOTIFICATION_PASSWORD:-}"
