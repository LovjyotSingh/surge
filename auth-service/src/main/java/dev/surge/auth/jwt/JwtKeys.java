package dev.surge.auth.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import dev.surge.common.security.SurgeEnv;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * The RSA keys behind the user JWTs (SEC-18).
 *
 * <ul>
 *   <li><b>Current key</b> ({@code surge.auth.jwt.key-file}, env {@code SURGE_JWT_KEY_FILE}): signs every token. A
 *       private RSA key of at least 2048 bits as PEM ({@code BEGIN PRIVATE KEY}, PKCS#8) or as a JWK. Its
 *       {@code kid} is the JWK's own {@code kid}, else its RFC 7638 thumbprint, so the same file gives the same
 *       {@code kid} on every start and tokens survive a restart (and work across replicas).</li>
 *   <li><b>Previous key</b> ({@code surge.auth.jwt.previous-key-file}, env {@code SURGE_JWT_PREVIOUS_KEY_FILE},
 *       optional): published in the JWKS next to the current key but never used to sign. PEM (public or private) or
 *       JWK; only its public half is kept.</li>
 * </ul>
 *
 * <p>Unset in {@code dev} (local runs, tests): a key is generated per start with a warning, so tokens stop working
 * when auth restarts. Unset in {@code prod}: startup fails. A location is {@code file:...}, {@code classpath:...} or
 * a plain file path. Nothing about the key material is ever logged or put in an exception message.
 *
 * <p><b>Rotation</b> without a single 401: (1) generate a new key and set it as the <em>previous</em> key next to the
 * old current one, restart auth and wait at least five minutes so the gateway's key cache picks it up; (2) swap them:
 * the new key becomes current, the old one previous, restart; (3) after 30 minutes (the token lifetime) the old key
 * can be dropped. Skipping step 1 also works, but tokens signed with the new key may get 401 for up to 30 s until the
 * gateway refetches the key set.
 */
public final class JwtKeys {

    private static final Logger log = LoggerFactory.getLogger(JwtKeys.class);

    public static final int MIN_BITS = 2048;
    /** 2048, not more: RS256 signing time grows steeply with the size, and the load test mints 20,000 tokens. */
    static final int GENERATED_BITS = 2048;
    static final String KEY_PROPERTY = "surge.auth.jwt.key-file (SURGE_JWT_KEY_FILE)";
    static final String PREVIOUS_PROPERTY = "surge.auth.jwt.previous-key-file (SURGE_JWT_PREVIOUS_KEY_FILE)";

    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]*)-----END \\1-----");
    private static final Pattern KID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:[\\\\/].*");

    private final RSAKey current;
    private final RSAKey previous;

    private JwtKeys(RSAKey current, RSAKey previous) {
        this.current = current;
        this.previous = previous;
    }

    /**
     * Loads the current key (generated in dev when {@code keyFile} is blank; refused in prod) and the optional
     * previous key.
     */
    public static JwtKeys load(String keyFile, String previousKeyFile, SurgeEnv env, ResourceLoader loader) {
        RSAKey current;
        if (keyFile == null || keyFile.isBlank()) {
            if (env == SurgeEnv.PROD) {
                throw new IllegalStateException(KEY_PROPERTY + " must be set when SURGE_ENV=prod; generate a key with "
                        + "docker/gen-jwt-key.sh");
            }
            current = generate();
            log.warn("No SURGE_JWT_KEY_FILE: generated a temporary JWT signing key; every token stops working when "
                    + "auth restarts (dev only)");
        } else {
            current = signingKey(read(keyFile, KEY_PROPERTY, loader), KEY_PROPERTY);
        }
        RSAKey previous = null;
        if (previousKeyFile != null && !previousKeyFile.isBlank()) {
            previous = publicKey(read(previousKeyFile, PREVIOUS_PROPERTY, loader), PREVIOUS_PROPERTY);
            if (previous.getKeyID().equals(current.getKeyID()) || thumbprint(previous).equals(thumbprint(current))) {
                throw new IllegalStateException(PREVIOUS_PROPERTY + " must hold a different key (and kid) than "
                        + KEY_PROPERTY);
            }
        }
        return new JwtKeys(current, previous);
    }

    /** The private signing key, with its {@code kid}, {@code alg RS256} and {@code use sig}. */
    public RSAKey current() {
        return current;
    }

    /** What the JWKS endpoint serves: the current key's public half first, then the previous key's, if any. */
    public JWKSet publicJwkSet() {
        List<JWK> keys = new ArrayList<>();
        keys.add(current.toPublicJWK());
        if (previous != null) {
            keys.add(previous.toPublicJWK());
        }
        return new JWKSet(keys);
    }

    static RSAKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(GENERATED_BITS);
            KeyPair pair = generator.generateKeyPair();
            return complete(new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey(pair.getPrivate()).build(),
                    null, "generated key");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate an RSA key", e);
        }
    }

    private static RSAKey signingKey(String text, String property) {
        RSAKey key = parse(text, property);
        if (!key.isPrivate()) {
            throw new IllegalStateException(property + " must hold a private RSA key (it signs the tokens)");
        }
        return key;
    }

    private static RSAKey publicKey(String text, String property) {
        return parse(text, property).toPublicJWK();
    }

    /** A PEM (PKCS#8 private key or X.509 public key) or a JWK, as an RSAKey with kid, alg and use filled in. */
    private static RSAKey parse(String text, String property) {
        String trimmed = text.strip();
        RSAKey key;
        String kid = null;
        if (trimmed.startsWith("{")) {
            JWK jwk;
            try {
                jwk = JWK.parse(trimmed);
            } catch (java.text.ParseException | RuntimeException e) {
                throw unreadable(property);
            }
            if (!(jwk instanceof RSAKey rsa)) {
                throw new IllegalStateException(property + " must hold an RSA key");
            }
            if (rsa.getKeyUse() != null && !KeyUse.SIGNATURE.equals(rsa.getKeyUse())) {
                throw new IllegalStateException(property + " is not a signature key (use)");
            }
            if (rsa.getAlgorithm() != null && !JWSAlgorithm.RS256.equals(rsa.getAlgorithm())) {
                throw new IllegalStateException(property + " is not an RS256 key (alg)");
            }
            key = rsa;
            kid = rsa.getKeyID();
        } else {
            key = fromPem(trimmed, property);
        }
        return complete(key, kid, property);
    }

    private static RSAKey fromPem(String text, String property) {
        Matcher m = PEM.matcher(text);
        if (!m.find()) {
            throw unreadable(property);
        }
        String type = m.group(1);
        byte[] der;
        try {
            der = Base64.getMimeDecoder().decode(m.group(2));
        } catch (IllegalArgumentException e) {
            throw unreadable(property);
        }
        try {
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            switch (type) {
                case "PRIVATE KEY" -> {
                    if (!(rsa.generatePrivate(new PKCS8EncodedKeySpec(der)) instanceof RSAPrivateCrtKey priv)) {
                        throw new IllegalStateException(property + " must be an RSA private key with its CRT "
                                + "parameters");
                    }
                    RSAPublicKey pub = (RSAPublicKey) rsa.generatePublic(
                            new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
                    return new RSAKey.Builder(pub).privateKey(priv).build();
                }
                case "PUBLIC KEY" -> {
                    return new RSAKey.Builder((RSAPublicKey) rsa.generatePublic(new X509EncodedKeySpec(der))).build();
                }
                case "RSA PRIVATE KEY" -> throw new IllegalStateException(property + " is a PKCS#1 key; convert it "
                        + "with: openssl pkcs8 -topk8 -nocrypt -in old.pem -out new.pem");
                default -> throw new IllegalStateException(property + " must be a PEM 'PRIVATE KEY' (PKCS#8) or "
                        + "'PUBLIC KEY', or a JWK");
            }
        } catch (GeneralSecurityException | ClassCastException e) {
            throw unreadable(property);
        }
    }

    /** Checks the size and sets kid (given, else the thumbprint), alg RS256 and use sig. */
    private static RSAKey complete(RSAKey key, String kid, String what) {
        if (key.size() < MIN_BITS) {
            throw new IllegalStateException(what + " must be an RSA key of at least 2048 bits");
        }
        String id = kid == null || kid.isBlank() ? thumbprint(key) : kid;
        if (!KID.matcher(id).matches()) {
            throw new IllegalStateException(what + " has a kid that is not 1-128 characters of [A-Za-z0-9._-]");
        }
        return new RSAKey.Builder(key).keyID(id).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
    }

    private static String thumbprint(RSAKey key) {
        try {
            return key.computeThumbprint().toString();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot compute a key thumbprint", e);
        }
    }

    private static IllegalStateException unreadable(String property) {
        return new IllegalStateException(property + " is not a readable PEM or JWK RSA key (details withheld so key "
                + "material is never logged)");
    }

    private static String read(String location, String property, ResourceLoader loader) {
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
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(property + " " + resource.getDescription() + " cannot be read: "
                    + e.getClass().getSimpleName());
        }
    }
}
