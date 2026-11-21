#!/bin/bash
# One MySQL user per service, with every privilege on its own database and none anywhere else (security audit
# SEC-05): booking's credentials cannot read auth.users. Passwords come from compose secret files (S10:
# docker/gen-secrets.sh writes docker/secrets/; compose mounts them into the mysql container only, at
# /run/secrets/env/<VARIABLE>); a variable of the same name, if set, wins (for running this by hand). The root
# password reaches this script as MYSQL_ROOT_PASSWORD, which the image's entrypoint exports from
# MYSQL_ROOT_PASSWORD_FILE.
#
# Run once by the mysql image's entrypoint (docker-entrypoint-initdb.d) on an EMPTY data directory, after
# 01-databases.sql. This file is executable (git keeps mode 755), so the entrypoint runs it as a program of its own:
# it does not inherit the entrypoint's `set -e`, and only its exit status counts. Hence the body below runs in a
# subshell with `set -euo pipefail`: the first command that fails (a bad password, a failing mysql call) ends the
# script non-zero, and the entrypoint then stops instead of starting MySQL without that user. If a copy without the
# executable bit is sourced instead, the subshell keeps those options out of the entrypoint (its own code is not
# written for `set -u`), and its failure still stops the entrypoint (which runs with `set -e`).
#
# Rotating a service password later: ALTER USER '<user>'@'%' IDENTIFIED BY '<new>'; as root, then update its file in
# docker/secrets/.

(
set -euo pipefail

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

# The variable if set, else its secret file (one trailing newline and CR dropped), else empty.
surge_secret() {
    local name=$1 file=/run/secrets/env/$1 value=""
    if [[ -n ${!name:-} ]]; then
        value=${!name}
    elif [[ -r $file ]]; then
        value=$(< "$file")
        value=${value%$'\r'}
    fi
    printf '%s' "$value"
}

surge_create_user auth auth "$(surge_secret MYSQL_AUTH_PASSWORD)"
surge_create_user booking booking "$(surge_secret MYSQL_BOOKING_PASSWORD)"
surge_create_user catalog catalog "$(surge_secret MYSQL_CATALOG_PASSWORD)"
surge_create_user notification notification "$(surge_secret MYSQL_NOTIFICATION_PASSWORD)"
)
