package dev.surge.auth.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** N1: the display name is plain words, so it cannot carry a link or a message. */
class SignupRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static boolean nameOk(String name) {
        return VALIDATOR.validate(new SignupRequest("a@example.com", "correct-horse", name)).stream()
                .noneMatch(v -> v.getPropertyPath().toString().equals("displayName"));
    }

    @Test
    void ordinaryNamesPass() {
        for (String name : List.of("Smoke Tester", "Ada", "J.R. Smith", "O'Brien-Lee", "José Ünal", "李雷 2", "A",
                "a".repeat(60), "lt-run1-7", "  Ada  ")) {
            assertThat(nameOk(name)).as(name).isTrue();
        }
    }

    @Test
    void linksMessagesAndOddCharactersAreRefused() {
        for (String name : List.of("http://evil.example", "https://evil.example/pay", "Visit evil.com now",
                "www.evil.com", "pay@evil.example", "Click: now", "<b>hi</b>", "a\nb", "line\r\nbreak", "100%", "a_b",
                "a/b", "a".repeat(61), "", "   ", "\u200bhidden", "evil.co")) {
            assertThat(nameOk(name)).as("[" + name + "]").isFalse();
        }
    }
}
