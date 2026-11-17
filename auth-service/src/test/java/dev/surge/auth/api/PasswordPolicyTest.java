package dev.surge.auth.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void theBundledListIsLoaded() {
        assertThat(policy.size()).isGreaterThan(5000);
    }

    @Test
    void commonPasswordsAreRefusedWhateverTheirCase() {
        for (String common : new String[] {"password1", "Password1", "PASSWORD123", "12345678", "qwerty123",
                "iloveyou", "Sunshine1", "1q2w3e4r", "football1", "welcome123"}) {
            assertThat(policy.problem("ada@example.com", common)).as(common).contains(PasswordPolicy.TOO_COMMON);
        }
    }

    @Test
    void passwordsContainingTheEmailsLocalPartAreRefused() {
        assertThat(policy.problem("asharani@example.com", "AshaRani-2026!")).contains(PasswordPolicy.CONTAINS_EMAIL);
        assertThat(policy.problem("Ada.K@Example.com", "my-ada.k-secret")).contains(PasswordPolicy.CONTAINS_EMAIL);
        assertThat(policy.problem("ada@example.com", "xxADAxx-horse")).contains(PasswordPolicy.CONTAINS_EMAIL);
        // a local part shorter than 3 characters is not checked
        assertThat(policy.problem("ab@example.com", "ab-correct-horse")).isEmpty();
    }

    @Test
    void ordinaryPasswordsPass() {
        assertThat(policy.problem("ada@example.com", "correct-horse-battery")).isEmpty();
        assertThat(policy.problem("ada@example.com", "hunter2hunter2")).isEmpty();
    }

    @Test
    void messagesNeverRepeatThePassword() {
        assertThat(PasswordPolicy.TOO_COMMON).doesNotContain("password1");
        assertThat(policy.problem("asharani@example.com", "AshaRani-2026!").orElseThrow()).doesNotContain("AshaRani");
    }
}
