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
#                                         docker inspect and /proc/<pid>/environ would show).
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
# Not generated (a public deployment, docs/DEPLOY.md): mail_password (your SMTP provider's), and real S3 keys written
# over s3_access_key and s3_secret_key.
#
# Every generated value is hex (URL-safe, no quotes or backslashes), so it is valid in a Eureka URL, a Kafka JAAS
# line, SQL and JSON alike: 48 characters (24 random bytes) for passwords, 64 for the ticket QR HMAC key.
#
# usage: docker/gen-secrets.sh [--force]
#   (none)    on a new checkout: write everything. With a .env from before S10 (passwords in .env): move each password
#             into its file in docker/secrets/, unchanged, and drop the password lines from .env, so a running stack
#             keeps its credentials. Otherwise refuse to replace anything.
#   --force   replace .env, every password and the keys in docker/secrets/. Note what a new value does on an existing
#             stack: Redis, RabbitMQ, Kafka, the Elasticsearch "catalog" user, Eureka, config and the admin password
#             follow docker/secrets/ at the next `docker compose up`. MySQL users and the "elastic" password are set
#             only when their volumes are first created: change them by hand (ALTER USER /
#             elasticsearch-reset-password) or start from new volumes. New JWT keys sign everyone out; new internal
#             keys need every service restarted.
#
# Needs: openssl, and a JDK for the key scripts (they run ./mvnw). Works on Linux, macOS and Git Bash.
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

command -v openssl > /dev/null 2>&1 || { echo "openssl is required" >&2; exit 1; }

umask 077
mkdir -p -- "$secrets"
chmod 700 "$secrets" || true

upper() {
    printf '%s' "$1" | tr '[:lower:]' '[:upper:]'
}

# <name> <value>: the password file, no trailing newline, readable by the container users (see above).
write_password() {
    tmp="$secrets/.$1.tmp.$$"
    printf '%s' "$2" > "$tmp"
    chmod 644 "$tmp" || true
    mv -f -- "$tmp" "$secrets/$1"
}

# .env without passwords: what compose interpolates (user names, the admin email).
write_env() {
    tmp_env="$env_file.tmp.$$"
    cat > "$tmp_env" <<EOF
# Generated by docker/gen-secrets.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ). Mode 600. NEVER commit this file.
# No password lives here: each one is a file in docker/secrets/ (compose mounts it as a secret). Rerun the script with
# --force to replace every value (read its notes on what changes for a running stack).

# HTTP Basic user names for Eureka (discovery) and the config server; the passwords are eureka_password and
# config_password.
EUREKA_USER=surge
CONFIG_USER=surge

# The seeded admin account (log in at /login); its password is docker/secrets/surge_admin_password.
SURGE_ADMIN_EMAIL=admin@surge.local

# Not generated. compose.dev.yaml points mail at Mailpit and S3 at S3Mock; compose.prod.yaml needs real ones (and
# docker/secrets/mail_password, plus real keys in s3_access_key and s3_secret_key). See docs/DEPLOY.md.
# MAIL_HOST=
# MAIL_PORT=587
# MAIL_USERNAME=
# MAIL_FROM=Surge <tickets@example.com>
# S3_ENDPOINT=          (empty = AWS)
# S3_REGION=ap-south-1
# S3_BUCKET=surge-tickets
# PUBLIC_BASE_URL=https://example.com
EOF
    chmod 600 "$tmp_env" || true
    mv -f -- "$tmp_env" "$env_file"
}

if [ -e "$env_file" ] && [ "$force" -ne 1 ]; then
    if ! grep -q '^MYSQL_ROOT_PASSWORD=' "$env_file"; then
        echo "$env_file already exists; refusing to replace it (use --force, and read its notes first)." >&2
        exit 1
    fi
    # A .env from before S10: move each password into its file, unchanged, so the running stack keeps working.
    for name in $passwords; do
        var=$(upper "$name")
        value=$(sed -n "s/^$var=//p" "$env_file" | tail -n 1 | tr -d '\r')
        if [ -z "$value" ]; then
            echo "$env_file has no $var; cannot move it into docker/secrets/$name. Rerun with --force (new values)." >&2
            exit 1
        fi
        [ -e "$secrets/$name" ] || write_password "$name" "$value"
    done
    pattern=$(for name in $passwords; do printf '%s|' "$(upper "$name")"; done)
    tmp_env="$env_file.tmp.$$"
    grep -Ev "^(${pattern%|})=" "$env_file" > "$tmp_env" || true
    printf '\n# S10: the passwords that were here are now files in docker/secrets/.\n' >> "$tmp_env"
    chmod 600 "$tmp_env" || true
    mv -f -- "$tmp_env" "$env_file"
    echo "Moved the passwords from $env_file into $secrets (one file each) and removed them from $env_file."
    echo "The keys, Redis ACLs and RabbitMQ definitions in $secrets were kept. No value was printed."
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

for name in $passwords; do
    case "$name" in
        surge_ticket_qr_key) value=$(secret 32) ;;
        s3_access_key) value=$(secret 10) ;;
        *) value=$(secret) ;;
    esac
    write_password "$name" "$value"
done
write_env

password() {
    cat -- "$secrets/$1"
}

# Templates are committed with LF endings; strip any CR a Windows checkout may have added anyway.
# Each Redis has its own template (docker/redis/<user>.acl.template): one user with only what its service uses.
redis_acl() { # <user> <password> <output file>
    grep -v '^#' "$root/docker/redis/$1.acl.template" | tr -d '\r' \
        | sed -e "s|@@REDIS_PASSWORD_SHA256@@|$(sha256_hex "$2")|" > "$3"
}
redis_acl booking "$(password redis_booking_password)" "$secrets/redis-users.acl"
redis_acl gateway "$(password redis_gateway_password)" "$secrets/redis-edge-users.acl"
tr -d '\r' < "$root/docker/rabbitmq/definitions.json.template" \
    | sed -e "s|@@ADMIN_HASH@@|$(rabbit_hash "$(password rabbit_admin_password)")|" \
          -e "s|@@BOOKING_HASH@@|$(rabbit_hash "$(password rabbit_booking_password)")|" \
          -e "s|@@NOTIFICATION_HASH@@|$(rabbit_hash "$(password rabbit_notification_password)")|" \
          > "$secrets/rabbitmq-definitions.json"
chmod 600 "$secrets/redis-users.acl" "$secrets/redis-edge-users.acl" "$secrets/rabbitmq-definitions.json" || true

key_flags=""
[ "$force" -eq 1 ] && key_flags="--force"
if [ -f "$secrets/internal-jwks.json" ] && [ "$force" -ne 1 ]; then
    echo "Keeping the existing internal identity keys in $secrets (use --force to replace them)."
else
    # shellcheck disable=SC2086 # $key_flags is empty or one fixed word
    sh "$root/docker/gen-internal-keys.sh" $key_flags "$secrets"
fi
if [ -f "$secrets/jwt.jwk" ] && [ "$force" -ne 1 ]; then
    echo "Keeping the existing JWT signing key $secrets/jwt.jwk (use --force to replace it)."
else
    # shellcheck disable=SC2086
    sh "$root/docker/gen-jwt-key.sh" $key_flags "$secrets/jwt.jwk"
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

echo "Wrote $env_file (mode 600, no passwords) and the files in $secrets. No value was printed."
echo "Admin sign-in: admin@surge.local with the password in docker/secrets/surge_admin_password."
