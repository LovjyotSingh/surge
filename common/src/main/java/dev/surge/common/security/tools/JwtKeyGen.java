package dev.surge.common.security.tools;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates auth-service's user-JWT signing key (run through {@code docker/gen-jwt-key.sh}): one private RSA JWK of
 * {@value #BITS} bits with {@code alg RS256}, {@code use sig} and {@code kid} = its RFC 7638 thumbprint, owner-only
 * where the file system supports POSIX permissions. Mount it into auth-service only and point
 * {@code SURGE_JWT_KEY_FILE} at it. An existing file is never overwritten unless {@code --force} is given; the key is
 * written to a staging file first and moved into place. Key material goes to the file only, never to the console.
 *
 * <pre>JwtKeyGen [--force] &lt;output-file&gt;</pre>
 */
public final class JwtKeyGen {

    /** RS256 with 2048 bits (NIST-acceptable through 2030); larger keys make every signature several times slower. */
    static final int BITS = 2048;

    private JwtKeyGen() {
    }

    public static void main(String[] args) {
        int code = run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args) {
        boolean force = false;
        List<String> positional = new ArrayList<>();
        for (String arg : args) {
            if ("--force".equals(arg)) {
                force = true;
            } else if (arg.startsWith("-")) {
                return usage();
            } else {
                positional.add(arg);
            }
        }
        if (positional.size() != 1) {
            return usage();
        }
        try {
            RSAKey key = generate(Path.of(positional.get(0)), force);
            System.out.println("wrote " + positional.get(0) + " (kid " + key.getKeyID() + ")");
            return 0;
        } catch (FileAlreadyExistsException e) {
            System.err.println("refusing to overwrite " + e.getFile() + " (pass --force to replace it)");
            return 1;
        } catch (IOException | JOSEException e) {
            System.err.println("key generation failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 1;
        }
    }

    private static int usage() {
        System.err.println("usage: JwtKeyGen [--force] <output-file>");
        return 2;
    }

    /** Writes a new key to {@code file} and returns it. */
    static RSAKey generate(Path file, boolean force) throws IOException, JOSEException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        if (!force && Files.exists(file)) {
            throw new FileAlreadyExistsException(file.toString());
        }
        RSAKey unnamed = new RSAKeyGenerator(BITS).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
        RSAKey key = new RSAKey.Builder(unnamed).keyID(unnamed.computeThumbprint().toString()).build();
        byte[] bytes = (key.toJSONString() + "\n").getBytes(StandardCharsets.UTF_8);

        boolean posix = Files.getFileAttributeView(dir, PosixFileAttributeView.class) != null;
        Path staging = posix
                ? Files.createTempFile(dir, ".jwt-key-", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(dir, ".jwt-key-", ".tmp");
        try {
            Files.write(staging, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                if (force) {
                    Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (AtomicMoveNotSupportedException e) {
                if (force) {
                    Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(staging, file);
                }
            }
        } finally {
            Files.deleteIfExists(staging);
        }
        return key;
    }
}
