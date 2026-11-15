package dev.surge.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Loads the internal identity keys: a private JWK for a signer ({@code surge.internal-identity.signing-key-location})
 * and the public JWKS for a verifier ({@code surge.internal-identity.jwks-location}). A location is {@code file:...},
 * {@code classpath:...} or a plain file path.
 *
 * <p>Unset in {@code dev}: the committed development keys under {@code classpath:surge-dev-keys/} are used, with a
 * loud warning. In {@code prod}: an unset location refuses to start, and so does any key whose thumbprint is one of
 * the development keys', however it was configured. Key material is never logged, not even in parse errors.
 */
public final class InternalKeys {

    private static final Logger log = LoggerFactory.getLogger(InternalKeys.class);

    static final String DEV_DIR = "classpath:surge-dev-keys/";
    static final String DEV_JWKS = DEV_DIR + "internal-jwks.json";
    static final String DEV_WARNING =
            "using the committed development internal-identity keys - never expose this instance";

    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:[\\\\/].*");
    private static volatile Set<String> devThumbprints;

    private InternalKeys() {
    }

    /** {@code gateway} → {@code gateway.jwk}, {@code svc:booking} → {@code booking.jwk}. */
    public static String devKeyFile(String issuer) {
        return (issuer.startsWith("svc:") ? issuer.substring(4) : issuer) + ".jwk";
    }

    public static ECKey loadSigningKey(String location, String issuer, SurgeEnv env, ResourceLoader loader) {
        String property = "surge.internal-identity.signing-key-location";
        boolean unset = location == null || location.isBlank();
        if (unset && env == SurgeEnv.PROD) {
            throw new IllegalStateException(property + " (SURGE_INTERNAL_SIGNING_KEY) must be set when SURGE_ENV=prod;"
                    + " generate keys with docker/gen-internal-keys.sh");
        }
        String effective = unset ? DEV_DIR + devKeyFile(issuer) : location;
        Resource resource = resolve(effective, property, loader);
        JWK jwk;
        try {
            jwk = JWK.parse(read(resource, property));
        } catch (java.text.ParseException | RuntimeException e) {
            throw new IllegalStateException(property + " " + resource.getDescription()
                    + " is not a valid JWK (details withheld so key material is never logged)");
        }
        if (!(jwk instanceof ECKey key) || !key.isPrivate()) {
            throw new IllegalStateException(property + " " + resource.getDescription()
                    + " must be a private EC P-256 JWK");
        }
        if (!issuer.equals(key.getKeyID())) {
            throw new IllegalStateException(property + " " + resource.getDescription() + " has kid '"
                    + key.getKeyID() + "' but this service signs as '" + issuer + "'");
        }
        guardDevKey(key, env, property, "signing key for " + issuer);
        return key;
    }

    public static JWKSet loadVerificationKeys(String location, SurgeEnv env, ResourceLoader loader) {
        String property = "surge.internal-identity.jwks-location";
        boolean unset = location == null || location.isBlank();
        if (unset && env == SurgeEnv.PROD) {
            throw new IllegalStateException(property + " (SURGE_INTERNAL_JWKS) must be set when SURGE_ENV=prod;"
                    + " generate keys with docker/gen-internal-keys.sh");
        }
        Resource resource = resolve(unset ? DEV_JWKS : location, property, loader);
        JWKSet jwks;
        try {
            jwks = JWKSet.parse(read(resource, property));
        } catch (java.text.ParseException | RuntimeException e) {
            throw new IllegalStateException(property + " " + resource.getDescription()
                    + " is not a valid JWKS (details withheld so key material is never logged)");
        }
        if (jwks.getKeys().isEmpty()) {
            throw new IllegalStateException(property + " " + resource.getDescription() + " holds no keys");
        }
        boolean anyDev = false;
        for (JWK key : jwks.getKeys()) {
            try {
                InternalTokenVerifier.requireVerificationKey(key);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(property + " " + resource.getDescription() + ": " + e.getMessage());
            }
            anyDev |= isDevKey(key);
        }
        if (anyDev) {
            refuseOrWarn(env, property, "verification keys");
        }
        return jwks;
    }

    /** SHA-256 JWK thumbprints (RFC 7638) of the committed development keys, from their public JWKS. */
    static Set<String> devThumbprints() {
        Set<String> thumbprints = devThumbprints;
        if (thumbprints == null) {
            Resource resource = new org.springframework.core.io.DefaultResourceLoader().getResource(DEV_JWKS);
            Set<String> computed = new HashSet<>();
            try {
                for (JWK key : JWKSet.parse(resource.getContentAsString(StandardCharsets.UTF_8)).getKeys()) {
                    computed.add(key.computeThumbprint().toString());
                }
            } catch (IOException | java.text.ParseException | JOSEException e) {
                throw new IllegalStateException("The development key list " + DEV_JWKS + " is unreadable", e);
            }
            thumbprints = Set.copyOf(computed);
            devThumbprints = thumbprints;
        }
        return thumbprints;
    }

    static boolean isDevKey(JWK key) {
        try {
            return devThumbprints().contains(key.computeThumbprint().toString());
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot compute a key thumbprint", e);
        }
    }

    private static void guardDevKey(JWK key, SurgeEnv env, String property, String what) {
        if (isDevKey(key)) {
            refuseOrWarn(env, property, what);
        }
    }

    private static void refuseOrWarn(SurgeEnv env, String property, String what) {
        if (env == SurgeEnv.PROD) {
            throw new IllegalStateException(property + " holds a committed development key (" + what
                    + "); SURGE_ENV=prod needs real keys from docker/gen-internal-keys.sh");
        }
        log.warn("{} ({})", DEV_WARNING, what);
    }

    private static Resource resolve(String location, String property, ResourceLoader loader) {
        String trimmed = location.trim();
        Resource resource;
        if (trimmed.startsWith("classpath:") || trimmed.startsWith("file:")) {
            resource = loader.getResource(trimmed);
        } else if (SCHEME.matcher(trimmed).find() && !WINDOWS_DRIVE.matcher(trimmed).matches()) {
            throw new IllegalStateException(property + " must be a file: or classpath: location or a file path");
        } else {
            resource = new FileSystemResource(trimmed);
        }
        if (!resource.exists()) {
            throw new IllegalStateException(property + " " + resource.getDescription() + " does not exist");
        }
        return resource;
    }

    private static String read(Resource resource, String property) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(property + " " + resource.getDescription() + " cannot be read: "
                    + e.getClass().getSimpleName());
        }
    }
}
