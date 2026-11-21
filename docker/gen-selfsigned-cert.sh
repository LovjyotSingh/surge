#!/bin/sh
# A self-signed certificate for web/nginx-tls.conf (S10): fullchain.pem and privkey.pem, the names certbot uses.
#
#   docker/gen-selfsigned-cert.sh                       # docker/tls/, for localhost (compose.tls-local.yaml)
#   sudo docker/gen-selfsigned-cert.sh /etc/surge/tls example.com
#                                                       # a placeholder so nginx can start before certbot has run
#                                                       # (docs/DEPLOY.md); the deploy hook then replaces it
#
# usage: docker/gen-selfsigned-cert.sh [directory] [hostname]
#   directory  default docker/tls (gitignored)
#   hostname   default localhost. The certificate also names localhost, 127.0.0.1 and ::1. Valid for 30 days.
#
# Browsers warn about it (no CA vouches for it) and never store HSTS from such a connection; curl needs -k.
#
# Permissions: nginx runs as uid/gid 101 and reads the directory through a read-only bind mount.
#   - Linux, as root: directory 750 and key 640, group 101 (nginx's group); certificate 644.
#   - Otherwise: directory 755 and key 644, so uid 101 can read it: acceptable for a throwaway local key only.
#     Docker Desktop (Windows, macOS) does not enforce these modes.
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
dir=${1:-$root/docker/tls}
host=${2:-localhost}

case "$host" in
    *[!A-Za-z0-9.-]* | "") echo "hostname must be letters, digits, dots and dashes" >&2; exit 2 ;;
esac
command -v openssl > /dev/null 2>&1 || { echo "openssl is required" >&2; exit 1; }

mkdir -p -- "$dir"
umask 077
# A config file rather than -subj: Git Bash would rewrite "/CN=..." into a Windows path.
names="DNS:$host"
[ "$host" = localhost ] || names="$names, DNS:localhost"
cfg=$(mktemp)
trap 'rm -f -- "$cfg"' EXIT
cat > "$cfg" <<EOF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $host
[ext]
subjectAltName = $names, IP:127.0.0.1, IP:::1
keyUsage = critical, digitalSignature
extendedKeyUsage = serverAuth
basicConstraints = critical, CA:FALSE
EOF
openssl req -x509 -config "$cfg" -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -sha256 -days 30 \
    -keyout "$dir/privkey.pem.tmp" -out "$dir/fullchain.pem.tmp" 2> /dev/null \
    || { echo "openssl could not create the certificate" >&2; exit 1; }
mv -f -- "$dir/privkey.pem.tmp" "$dir/privkey.pem"
mv -f -- "$dir/fullchain.pem.tmp" "$dir/fullchain.pem"
chmod 644 "$dir/fullchain.pem" || true

if [ "$(uname -s 2> /dev/null || echo unknown)" = Linux ] && [ "$(id -u)" -eq 0 ]; then
    chgrp 101 "$dir" "$dir/privkey.pem" "$dir/fullchain.pem"
    chmod 750 "$dir"
    chmod 640 "$dir/privkey.pem"
else
    chmod 755 "$dir" || true
    chmod 644 "$dir/privkey.pem" || true
fi

echo "Wrote a self-signed certificate for $host (30 days) to $dir/fullchain.pem and $dir/privkey.pem."
