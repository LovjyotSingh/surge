package dev.surge.common.security.tools;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generates the internal identity keys (run through {@code docker/gen-internal-keys.sh}):
 * {@code gateway.jwk}, {@code booking.jwk}, {@code notification.jwk} (private P-256 JWKs, {@code kid} = issuer name,
 * owner-only where the file system supports it) and {@code internal-jwks.json} (every public half). Existing files are
 * never overwritten unless {@code --force} is given; {@code --dev} labels the files DEV ONLY. Key material is written
 * to the files only, never to the console.
 *
 * <pre>InternalKeyGen [--force] [--dev] &lt;output-dir&gt;</pre>
 */
public final class InternalKeyGen {

    /** Issuer name → private key file name. */
    static final Map<String, String> FILES = Map.of("gateway", "gateway.jwk", "svc:booking", "booking.jwk",
            "svc:notification", "notification.jwk");
    static final List<String> ISSUERS = List.of("gateway", "svc:booking", "svc:notification");
    static final String JWKS_FILE = "internal-jwks.json";
    static final String DEV_NOTE = "DEV ONLY - committed on purpose for local runs and tests. Anyone can read it, so "
            + "SURGE_ENV=prod refuses this key. Real keys: docker/gen-internal-keys.sh";

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private InternalKeyGen() {
    }

    public static void main(String[] args) {
        int code = run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args) {
        boolean force = false;
        boolean dev = false;
        List<String> positional = new ArrayList<>();
        for (String arg : args) {
            switch (arg) {
                case "--force" -> force = true;
                case "--dev" -> dev = true;
                default -> {
                    if (arg.startsWith("-")) {
                        return usage();
                    }
                    positional.add(arg);
                }
            }
        }
        if (positional.size() != 1) {
            return usage();
        }
        try {
            for (Path written : generate(Path.of(positional.get(0)), force, dev)) {
                System.out.println("wrote " + written);
            }
            return 0;
        } catch (FileAlreadyExistsException e) {
            System.err.println("refusing to overwrite " + e.getFile() + " (pass --force to replace every key)");
            return 1;
        } catch (IOException | JOSEException e) {
            System.err.println("key generation failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 1;
        }
    }

    private static int usage() {
        System.err.println("usage: InternalKeyGen [--force] [--dev] <output-dir>");
        return 2;
    }

    /** Writes all four files, or none when one exists and {@code force} is off. Returns the paths written. */
    static List<Path> generate(Path dir, boolean force, boolean dev) throws IOException, JOSEException {
        Files.createDirectories(dir);
        List<Path> targets = new ArrayList<>();
        for (String issuer : ISSUERS) {
            targets.add(dir.resolve(FILES.get(issuer)));
        }
        targets.add(dir.resolve(JWKS_FILE));
        if (!force) {
            for (Path target : targets) {
                if (Files.exists(target)) {
                    throw new FileAlreadyExistsException(target.toString());
                }
            }
        }

        List<Map<String, Object>> publicKeys = new ArrayList<>();
        List<Path> written = new ArrayList<>();
        for (String issuer : ISSUERS) {
            ECKey key = new ECKeyGenerator(Curve.P_256).keyID(issuer).keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.ES256).generate();
            Path file = dir.resolve(FILES.get(issuer));
            write(file, labelled(key.toJSONObject(), dev), "rw-------");
            written.add(file);
            publicKeys.add(key.toPublicJWK().toJSONObject());
        }
        Map<String, Object> jwks = new LinkedHashMap<>();
        if (dev) {
            jwks.put("x-surge-note", DEV_NOTE);
        }
        jwks.put("keys", publicKeys);
        Path jwksFile = dir.resolve(JWKS_FILE);
        write(jwksFile, jwks, "rw-r--r--");
        written.add(jwksFile);
        return written;
    }

    private static Map<String, Object> labelled(Map<String, Object> jwk, boolean dev) {
        if (!dev) {
            return jwk;
        }
        Map<String, Object> labelled = new LinkedHashMap<>();
        labelled.put("x-surge-note", DEV_NOTE);
        labelled.putAll(jwk);
        return labelled;
    }

    private static void write(Path file, Map<String, Object> json, String posixPermissions) throws IOException {
        byte[] bytes = (JSON.writeValueAsString(json) + "\n").getBytes(StandardCharsets.UTF_8);
        boolean posix = Files.getFileAttributeView(file.getParent(), PosixFileAttributeView.class) != null;
        Files.deleteIfExists(file);
        if (posix) {
            // created with its final permissions: a private key is never readable by others, not even briefly
            FileAttribute<?> permissions = PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString(posixPermissions));
            Files.createFile(file, permissions);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(posixPermissions));
        } else {
            Files.createFile(file);
        }
        Files.write(file, bytes, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
