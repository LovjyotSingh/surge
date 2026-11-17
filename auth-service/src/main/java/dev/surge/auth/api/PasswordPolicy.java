package dev.surge.auth.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Signup password rules beyond the length checks on {@code SignupRequest} (S5, SEC-23). A password is refused when
 * it is one of the common passwords in {@code security/common-passwords.txt} (compared ignoring case; the file's
 * header states its source and licence), or when it contains the part of the email before the {@code @}, ignoring
 * case, once that part is at least {@value #MIN_LOCAL_PART} characters long. The messages never repeat the password.
 */
@Component
public class PasswordPolicy {

    static final String LIST = "security/common-passwords.txt";
    static final int MIN_LOCAL_PART = 3;
    static final String TOO_COMMON = "is one of the most common passwords; choose one that is harder to guess";
    static final String CONTAINS_EMAIL = "must not contain the part of your email address before the @";

    private final Set<String> common;

    public PasswordPolicy() {
        this.common = load();
    }

    /** Why {@code password} is refused for an account with {@code email}, or empty when it is acceptable. */
    public Optional<String> problem(String email, String password) {
        String lower = password.toLowerCase(Locale.ROOT);
        if (common.contains(lower)) {
            return Optional.of(TOO_COMMON);
        }
        int at = email.indexOf('@');
        String local = (at < 0 ? email : email.substring(0, at)).trim().toLowerCase(Locale.ROOT);
        if (local.length() >= MIN_LOCAL_PART && lower.contains(local)) {
            return Optional.of(CONTAINS_EMAIL);
        }
        return Optional.empty();
    }

    int size() {
        return common.size();
    }

    private static Set<String> load() {
        Set<String> entries = new HashSet<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new ClassPathResource(LIST).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String entry = line.strip();
                if (!entry.isEmpty() && !entry.startsWith("#")) {
                    entries.add(entry.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The common-password list " + LIST + " is unreadable", e);
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("The common-password list " + LIST + " is empty");
        }
        return Set.copyOf(entries);
    }
}
