#!/bin/sh
# Generates every credential and key the Compose platform needs (security audit Task C, §5 step 4):
#
#   .env                                  mode 600, read by docker compose: user names and settings, NO password
#   docker/secrets/                       mode 700 (gitignored)
#     <name>                              one password or key per file, no newline (S10): mysql_root_password,
#                                         mysql_{auth,booking,catalog,notification}_password,
#                                         redis_{booking,gateway}_password, rabbit_{admin,booking,notification}_password,
#                                         kafka_{admin,booking,catalog,notification}_password, elastic_password,
#                                         elasticsearch_catalog_password, eureka_password, config_password,
#                                         surge_admin_password, surge_ticket_qr_key, s3_access_key, s3_secret_key.
#                                         compose.yaml mounts each one only where it is used, at
#                                         /run/secrets/env/<VARIABLE>, never as an environment variable (which
#                                         docker inspect would show).
#     jwt.jwk                             auth-service's user-JWT signing key (docker/gen-jwt-key.sh)
#     gateway.jwk booking.jwk notification.jwk
#                                         internal identity signing keys, one per issuer (docker/gen-internal-keys.sh)
#     internal-jwks.json                  their public halves (mode 644)
#     redis-users.acl                     booking's Redis ACL: default user off, user "booking"
#     redis-edge-users.acl                the gateway's Redis ACL: default user off, user "gateway"
#                                         (from docker/redis/<user>.acl.template: each user gets only the commands,
#                                         keys and channels its service uses; only the SHA-256 of the password)
#     rabbitmq-definitions.json           RabbitMQ users (salted SHA-256), vhost, permissions and ticket topology
#
# Provider secrets: s3_access_key and s3_secret_key start as random placeholders (S3Mock accepts any key); on a real
# deployment you write your provider's keys over them (docker/set-secret.sh), and mail_password is yours alone.
# This script never replaces those three once they exist, not even with --force.
#
# Every generated value is hex (URL-safe, no quotes or backslashes), so it is valid in a Eureka URL, a Kafka JAAS
# line, SQL and JSON alike: 48 characters (24 random bytes) for passwords, 64 for the ticket QR HMAC key.
#
# usage: docker/gen-secrets.sh [--force]
#   (none)    Writes only what is MISSING and never replaces an existing secret file: on a new checkout that is
#             everything; with a lost .env, only .env (its defaults); with one file deleted, only that file (and the
#             Redis ACL or RabbitMQ definitions that depend on it). With a .env from before S10 (passwords in .env), it
#             moves each password into its file, unchanged, and drops the password lines from .env, so a running stack
#             keeps its credentials.
#   --force   Replaces every generated password and the keys, and renders the ACLs and definitions again. It keeps
#             the provider secrets above and every line of .env it does not manage (MAIL_*, S3_*, PUBLIC_BASE_URL,
#             SURGE_SERVER_NAME, ...), and keeps EUREKA_USER, CONFIG_USER and SURGE_ADMIN_EMAIL. What a new value does
#             on an existing stack: Redis, RabbitMQ, Kafka, the Elasticsearch "catalog" user, Eureka, config and the
#             admin password follow their files once their containers are recreated (docker compose up -d
#             --force-recreate). MySQL users and the "elastic" password are set only when their volumes are created:
#             change them in the store first (ALTER USER / elasticsearch-reset-password) or start from new volumes
#             (down -v). New JWT keys sign everyone out; new internal keys need every service recreated.
#
# Needs: openssl, and a JDK (java on PATH or JAVA_HOME) whenever keys are generated: the key scripts run ./mvnw. It
# checks both before writing anything. Works on Linux, macOS and Git Bash.
#
# File permissions: docker/secrets/ is mode 700, so no other user of the host can read anything in it.
#   - Password files are mode 644 inside it: several containers, each with its own uid, read the same one (MySQL
#     uid 999 and the service, uid 10001, both read a database password). A container sees only the files compose
#     mounts into it.
#   - The keys, ACLs and RabbitMQ definitions are owner-only (600) and have one reader each. Linux, run as root (the
#     deployment host): each is chowned to the uid that reads it. Linux, not root: if setfacl exists, that uid gets a
#     read ACL; otherwise the script prints the chown commands.
#   - Docker Desktop (Windows, macOS) shares files without enforcing these modes; nothing to do.
set -eu

usage() {
    echo "usage: $0 [--force]" >&2
    exit 2
}

force=0
case "${1:-}" in
    --force) force=1 ;;
    "") ;;
    *) usage ;;
esac
[ $# -le 1 ] || usage

root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
env_file="$root/.env"
secrets="$root/docker/secrets"

# Every password file; the upper-case name is its variable in a .env from before S10.
passwords="mysql_root_password mysql_auth_password mysql_booking_password mysql_catalog_password
mysql_notification_password redis_booking_password redis_gateway_password rabbit_admin_password
rabbit_booking_password rabbit_notification_password kafka_admin_password kafka_booking_password
kafka_catalog_password kafka_notification_password elastic_password elasticsearch_catalog_password eureka_password
config_password surge_admin_password surge_ticket_qr_key s3_access_key s3_secret_key"
# Written once, then only by you (see "Provider secrets" above).
provider_secrets="s3_access_key s3_secret_key"
# The .env lines this script owns; every other line of an existing .env is kept.
managed_env="EUREKA_USER CONFIG_USER SURGE_ADMIN_EMAIL"

command -v openssl > /dev/null 2>&1 || { echo "openssl is required" >&2; exit 1; }

have_java() {
    if [ -n "${JAVA_HOME:-}" ] && { [ -x "$JAVA_HOME/bin/java" ] || [ -x "$JAVA_HOME/bin/java.exe" ]; }; then
        return 0
    fi
    command -v java > /dev/null 2>&1
}

upper() {
    printf '%s' "$1" | tr '[:lower:]' '[:upper:]'
}

contains() { # <word> <list>
    case " $(echo "$2" | tr '\n' ' ') " in
        *" $1 "*) return 0 ;;
    esac
    return 1
}

# The last value of <VARIABLE> in the current .env, CR stripped; empty when unset or no .env.
env_value() {
    [ -f "$env_file" ] || return 0
    sed -n "s/^$1=//p" "$env_file" | tail -n 1 | tr -d '\r'
}

# <name> <value>: the password file, no trailing newline, readable by the container users (see above).
write_password() {
    tmp="$secrets/.$1.tmp.$$"
    printf '%s' "$2" > "$tmp"
    chmod 644 "$tmp" || true
    mv -f -- "$tmp" "$secrets/$1"
}

# .env without passwords: the user names and admin email (kept from the current .env if set), then every line of
# the current .env this script does not own (MAIL_*, S3_*, PUBLIC_BASE_URL, ...), then commented hints.
write_env() {
    eureka_user=$(env_value EUREKA_USER)
    config_user=$(env_value CONFIG_USER)
    admin_email=$(env_value SURGE_ADMIN_EMAIL)
    password_vars=$(for name in $passwords; do upper "$name"; printf ' '; done)
    tmp_env="$env_file.tmp.$$"
    {
        echo "# Generated by docker/gen-secrets.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ). Mode 600. NEVER commit this file."
        echo "# No password lives here: each one is a file in docker/secrets/ (compose mounts it as a secret)."
        echo
        echo "# HTTP Basic user names for Eureka (discovery) and the config server; the passwords are eureka_password"
        echo "# and config_password."
        echo "EUREKA_USER=${eureka_user:-surge}"
        echo "CONFIG_USER=${config_user:-surge}"
        echo
        echo "# The seeded admin account (log in at /login); its password is docker/secrets/surge_admin_password."
        echo "SURGE_ADMIN_EMAIL=${admin_email:-admin@surge.local}"
        if [ -f "$env_file" ]; then
            kept=""
            while IFS= read -r line || [ -n "$line" ]; do
                line=$(printf '%s' "$line" | tr -d '\r')
                # KEY=value lines only (comments, blank lines and anything else are dropped)
                case "$line" in
                    [A-Za-z_]*=*) ;;
                    *) continue ;;
                esac
                key=${line%%=*}
                case "$key" in
                    *[!A-Za-z0-9_]*) continue ;;
                esac
                contains "$key" "$managed_env" && continue
                contains "$key" "$password_vars" && continue
                kept="$kept$line
"
            done < "$env_file"
            if [ -n "$kept" ]; then
                echo
                echo "# Kept from the previous .env."
                printf '%s' "$kept"
            fi
        fi
        cat <<'EOF'

# Not generated. compose.dev.yaml points mail at Mailpit and S3 at S3Mock; compose.prod.yaml needs real ones (and
# docker/secrets/mail_password, plus real keys in s3_access_key and s3_secret_key). See docs/DEPLOY.md.
# SURGE_SERVER_NAME=surge.example.com
# MAIL_HOST=
# MAIL_PORT=587
# MAIL_USERNAME=
# MAIL_FROM=Surge <tickets@example.com>
# S3_ENDPOINT=          (empty = AWS)
# S3_REGION=ap-south-1
# S3_BUCKET=surge-tickets
# PUBLIC_BASE_URL=https://example.com
EOF
    } > "$tmp_env"
    chmod 600 "$tmp_env" || true
    mv -f -- "$tmp_env" "$env_file"
}

need_keys=0
if [ "$force" -eq 1 ] || [ ! -f "$secrets/internal-jwks.json" ] || [ ! -f "$secrets/jwt.jwk" ]; then
    need_keys=1
fi
if [ "$need_keys" -eq 1 ] && ! have_java; then
    echo "The JWT and internal identity keys are generated with ./mvnw, which needs a JDK: put java on PATH or set" \
         "JAVA_HOME, then run this again. Nothing was written." >&2
    exit 1
fi

umask 077
mkdir -p -- "$secrets"
chmod 700 "$secrets" || true

if [ "$force" -ne 1 ] && [ -f "$env_file" ] && grep -q '^MYSQL_ROOT_PASSWORD=' "$env_file"; then
    # A .env from before S10: move each password into its file, unchanged, so the running stack keeps working.
    for name in $passwords; do
        [ ! -e "$secrets/$name" ] || continue
        var=$(upper "$name")
        value=$(env_value "$var")
        if [ -z "$value" ]; then
            echo "$env_file has no $var; cannot move it into docker/secrets/$name. Rerun with --force (new values)." >&2
            exit 1
        fi
        write_password "$name" "$value"
    done
    write_env
    echo "Moved the passwords from $env_file into $secrets (one file each) and removed them from $env_file."
    echo "Existing files in $secrets were kept. No value was printed."
    exit 0
fi

secret() {
    openssl rand -hex "${1:-24}"
}

# RabbitMQ's rabbit_password_hashing_sha256: base64(salt + sha256(salt + password)), with a 4-byte random salt.
rabbit_hash() {
    tmp=$(mktemp -d)
    openssl rand 4 > "$tmp/salt"
    printf '%s' "$1" > "$tmp/password"
    cat "$tmp/salt" "$tmp/password" | openssl dgst -sha256 -binary > "$tmp/digest"
    cat "$tmp/salt" "$tmp/digest" | openssl base64 -A
    rm -rf -- "$tmp"
}

sha256_hex() {
    printf '%s' "$1" | openssl dgst -sha256 | sed 's/^.*= *//'
}

# Passwords: without --force only the missing ones; with --force every one but the provider secrets.
written=""
for name in $passwords; do
    if [ -e "$secrets/$name" ]; then
        [ "$force" -eq 1 ] || continue
        contains "$name" "$provider_secrets" && continue
    fi
    case "$name" in
        surge_ticket_qr_key) value=$(secret 32) ;;
        s3_access_key) value=$(secret 10) ;;
        *) value=$(secret) ;;
    esac
    write_password "$name" "$value"
    written="$written $name"
done
if [ "$force" -eq 1 ] || [ ! -f "$env_file" ]; then
    write_env
    written="$written .env"
fi

password() {
    cat -- "$secrets/$1"
}

# A derived file is rendered when it is missing, or when one of its passwords was just written.
needs_render() { # <file> <password names...>
    file=$1
    shift
    [ -f "$secrets/$file" ] || return 0
    for name in "$@"; do
        contains "$name" "$written" && return 0
    done
    return 1
}

# Templates are committed with LF endings; strip any CR a Windows checkout may have added anyway.
# Each Redis has its own template (docker/redis/<user>.acl.template): one user with only what its service uses.
redis_acl() { # <user> <password> <output file>
    grep -v '^#' "$root/docker/redis/$1.acl.template" | tr -d '\r' \
        | sed -e "s|@@REDIS_PASSWORD_SHA256@@|$(sha256_hex "$2")|" > "$3"
    chmod 600 "$3" || true
}
if needs_render redis-users.acl redis_booking_password; then
    redis_acl booking "$(password redis_booking_password)" "$secrets/redis-users.acl"
    written="$written redis-users.acl"
fi
if needs_render redis-edge-users.acl redis_gateway_password; then
    redis_acl gateway "$(password redis_gateway_password)" "$secrets/redis-edge-users.acl"
    written="$written redis-edge-users.acl"
fi
if needs_render rabbitmq-definitions.json rabbit_admin_password rabbit_booking_password rabbit_notification_password
then
    tr -d '\r' < "$root/docker/rabbitmq/definitions.json.template" \
        | sed -e "s|@@ADMIN_HASH@@|$(rabbit_hash "$(password rabbit_admin_password)")|" \
              -e "s|@@BOOKING_HASH@@|$(rabbit_hash "$(password rabbit_booking_password)")|" \
              -e "s|@@NOTIFICATION_HASH@@|$(rabbit_hash "$(password rabbit_notification_password)")|" \
              > "$secrets/rabbitmq-definitions.json"
    chmod 600 "$secrets/rabbitmq-definitions.json" || true
    written="$written rabbitmq-definitions.json"
fi

key_flags=""
[ "$force" -eq 1 ] && key_flags="--force"
if [ -f "$secrets/internal-jwks.json" ] && [ "$force" -ne 1 ]; then
    :
else
    # shellcheck disable=SC2086 # $key_flags is empty or one fixed word
    sh "$root/docker/gen-internal-keys.sh" $key_flags "$secrets"
    written="$written internal-keys"
fi
if [ -f "$secrets/jwt.jwk" ] && [ "$force" -ne 1 ]; then
    :
else
    # shellcheck disable=SC2086
    sh "$root/docker/gen-jwt-key.sh" $key_flags "$secrets/jwt.jwk"
    written="$written jwt.jwk"
fi

# Let the container user read its own files (see "File permissions" above).
grant_read() {
    file=$1
    uid=$2
    case "$(uname -s 2> /dev/null || echo unknown)" in
        Linux) ;;
        *) return 0 ;;
    esac
    if [ "$(id -u)" -eq 0 ]; then
        chown "$uid" "$file"
    elif command -v setfacl > /dev/null 2>&1; then
        setfacl -m "u:$uid:r" "$file"
    else
        echo "WARNING: uid $uid cannot read $file (mode 600). Run: sudo chown $uid '$file'" >&2
    fi
}
for key in jwt gateway booking notification; do
    grant_read "$secrets/$key.jwk" 10001
done
grant_read "$secrets/redis-users.acl" 999
grant_read "$secrets/redis-edge-users.acl" 999
grant_read "$secrets/rabbitmq-definitions.json" 999

if [ -z "$written" ]; then
    echo "Every secret and .env already exist; nothing was written (use --force to replace the generated ones)."
else
    echo "Wrote:$written. Existing files were kept. No value was printed."
    echo "Admin sign-in: $(env_value SURGE_ADMIN_EMAIL) with the password in docker/secrets/surge_admin_password."
fi
