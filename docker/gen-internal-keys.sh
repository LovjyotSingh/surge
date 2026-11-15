#!/bin/sh
# Generates the keys for Surge's internal identity tokens (security audit section 3, task S2):
#
#   <out>/gateway.jwk, <out>/booking.jwk, <out>/notification.jwk   private ES256 (P-256) JWKs, kid = issuer, mode 600
#   <out>/internal-jwks.json                                       every public half, mounted into every service
#
# Mount each private key only into its owner (gateway, booking-service, notification-service) and point
# SURGE_INTERNAL_SIGNING_KEY / SURGE_INTERNAL_JWKS at the files. Never commit the output.
#
# usage: docker/gen-internal-keys.sh [--force] [--dev] <output-dir>
#   --force   replace existing keys (every running service must then get the new files)
#   --dev     label the files DEV ONLY; used once for the committed keys in common/src/main/resources/surge-dev-keys
#
# Needs a JDK (JAVA_HOME or java on PATH); Maven comes from ./mvnw. Works on Linux, macOS and Git Bash. The keys
# are made by dev.surge.common.security.tools.InternalKeyGen (Nimbus), so no openssl/jq JWK conversion is needed.
# With --force all four files are generated in a staging directory first and then moved into place.
#
# Permissions: on Linux/macOS the private keys are created owner-only (600). On Windows (Git Bash) file modes do not
# exist: chmod below is best-effort and changes nothing real; the files get the output directory's ACL, so keep that
# directory private. Generate production keys on the Linux host that runs the containers.
set -eu

usage() {
    echo "usage: $0 [--force] [--dev] <output-dir>" >&2
    exit 2
}

flags=""
while [ $# -gt 0 ]; do
    case "$1" in
        --force|--dev) flags="$flags $1"; shift ;;
        -*) usage ;;
        *) break ;;
    esac
done
[ $# -eq 1 ] || usage
out=$1

root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
umask 077
mkdir -p -- "$out"

case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*)
        # java.exe is a Windows program: Windows-style absolute path and ';' between classpath entries
        sep=';'
        out_abs=$(CDPATH='' cd -- "$out" && pwd -W)
        ;;
    *)
        sep=':'
        out_abs=$(CDPATH='' cd -- "$out" && pwd)
        ;;
esac

if [ -n "${JAVA_HOME:-}" ]; then
    java="$JAVA_HOME/bin/java"
else
    java=java
fi

cd -- "$root"
./mvnw -q -B -pl common compile dependency:build-classpath \
    -Dmdep.outputFile=target/internal-keygen.classpath -Dmdep.includeScope=runtime
classpath="common/target/classes${sep}$(cat common/target/internal-keygen.classpath)"

# shellcheck disable=SC2086 # $flags is a list of fixed words
"$java" -cp "$classpath" dev.surge.common.security.tools.InternalKeyGen $flags "$out_abs"

cd - > /dev/null
# Best-effort: real on Linux/macOS, a no-op on Windows (see Permissions above).
chmod 600 "$out"/gateway.jwk "$out"/booking.jwk "$out"/notification.jwk || true
chmod 644 "$out"/internal-jwks.json || true
echo "Done. Private keys are owner-only on Linux/macOS (see Permissions for Windows); internal-jwks.json is public."
