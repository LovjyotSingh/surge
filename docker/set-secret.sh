#!/bin/sh
# Writes one secret file in docker/secrets/ from standard input, so the value never appears on a command line, in
# `ps` or in your shell history (S10). For the secrets docker/gen-secrets.sh does not make: your SMTP provider's
# password and your S3 keys (or to replace any other one by hand).
#
#   sudo docker/set-secret.sh mail_password            # prompts; what you type is not shown
#   pass show surge/smtp | sudo docker/set-secret.sh mail_password
#
# usage: docker/set-secret.sh <name>
#   name   the file name in docker/secrets/: lower-case letters, digits and _ (e.g. mail_password, s3_access_key,
#          s3_secret_key, surge_admin_password)
#
# The value is the first line read (a trailing CR is dropped), written without a newline, mode 644 inside the mode 700
# directory (see docker/gen-secrets.sh, "File permissions"). An empty value is refused. A running container keeps the
# old value until it is recreated: docker compose ... up -d --force-recreate <service>.
set -eu

[ $# -eq 1 ] || { echo "usage: $0 <name>" >&2; exit 2; }
name=$1
case "$name" in
    "" | *[!a-z0-9_]*) echo "the name must be lower-case letters, digits and _ only" >&2; exit 2 ;;
esac

root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
secrets="$root/docker/secrets"
umask 077
mkdir -p -- "$secrets"
chmod 700 "$secrets" || true

if [ -t 0 ]; then
    printf 'Value for %s (not shown): ' "$name" >&2
    stty -echo
    trap 'stty echo' EXIT INT TERM
    IFS= read -r value || true
    stty echo
    trap - EXIT INT TERM
    printf '\n' >&2
else
    IFS= read -r value || true
fi
value=$(printf '%s' "$value" | tr -d '\r')
[ -n "$value" ] || { echo "empty value; nothing written" >&2; exit 1; }

tmp="$secrets/.$name.tmp.$$"
printf '%s' "$value" > "$tmp"
chmod 644 "$tmp" || true
mv -f -- "$tmp" "$secrets/$name"
echo "Wrote $secrets/$name. The value was not printed."
