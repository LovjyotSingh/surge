#!/bin/sh
# Generates auth-service's user-JWT signing key (security audit SEC-18, task S7):
#
#   <file>   a private RSA JWK (2048 bits, alg RS256, use sig, kid = its RFC 7638 thumbprint), mode 600
#
# Mount it into auth-service ONLY and point SURGE_JWT_KEY_FILE at it. Tokens then survive auth restarts, and every
# auth replica signs with the same key. Never commit the output. SURGE_ENV=prod refuses to start without a key file.
#
# Rotation (see auth-service/.../jwt/JwtKeys.java): generate a second key, publish it first through
# SURGE_JWT_PREVIOUS_KEY_FILE, then swap the two files.
# A COMPROMISED key: replace it (no previous key), restart auth, then RESTART EVERY GATEWAY. The gateway caches
# auth's key set and keeps the last good set when auth is unreachable, so only a restart drops the stolen key at once.
#
# usage: docker/gen-jwt-key.sh [--force] <output-file>
#   --force   replace an existing key (every token signed with the old key stops working unless it is kept as the
#             previous key)
#
# Needs a JDK (JAVA_HOME or java on PATH); Maven comes from ./mvnw. Works on Linux, macOS and Git Bash. The key is
# made by dev.surge.common.security.tools.JwtKeyGen (Nimbus), so no openssl is needed. auth also accepts a PKCS#8 PEM,
# e.g. from: openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt.pem
#
# Permissions: on Linux/macOS the key is created owner-only (600). On Windows (Git Bash) file modes do not exist: the
# file gets its directory's ACL, so keep that directory private. Generate production keys on the Linux host.
set -eu

usage() {
    echo "usage: $0 [--force] <output-file>" >&2
    exit 2
}

flags=""
while [ $# -gt 0 ]; do
    case "$1" in
        --force) flags="$flags $1"; shift ;;
        -*) usage ;;
        *) break ;;
    esac
done
[ $# -eq 1 ] || usage
out=$1

root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
umask 077
out_dir=$(dirname -- "$out")
mkdir -p -- "$out_dir"

case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*)
        # java.exe is a Windows program: Windows-style absolute path and ';' between classpath entries
        sep=';'
        out_abs="$(CDPATH='' cd -- "$out_dir" && pwd -W)/$(basename -- "$out")"
        ;;
    *)
        sep=':'
        out_abs="$(CDPATH='' cd -- "$out_dir" && pwd)/$(basename -- "$out")"
        ;;
esac

if [ -n "${JAVA_HOME:-}" ]; then
    java="$JAVA_HOME/bin/java"
else
    java=java
fi

cd -- "$root"
./mvnw -q -B -pl common compile dependency:build-classpath \
    -Dmdep.outputFile=target/jwt-keygen.classpath -Dmdep.includeScope=runtime
classpath="common/target/classes${sep}$(cat common/target/jwt-keygen.classpath)"

# shellcheck disable=SC2086 # $flags is a list of fixed words
"$java" -cp "$classpath" dev.surge.common.security.tools.JwtKeyGen $flags "$out_abs"

cd - > /dev/null
# Best-effort: real on Linux/macOS, a no-op on Windows (see Permissions above).
chmod 600 "$out" || true
echo "Done. Set SURGE_JWT_KEY_FILE for auth-service to this file (mounted read-only, auth-service only)."
