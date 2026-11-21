package dev.surge.auth.verify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** N1: the code email carries no text the sender typed, and still carries the code. */
class VerificationMailerContentTest {

    @Test
    void theEmailHasTheCodeAndNoGreetingWithAName() {
        assertThat(VerificationMailer.text("123456")).contains("Your Surge verification code is:").contains("123456")
                .doesNotContain("Hi ");
        assertThat(VerificationMailer.html("123456")).contains("Your Surge verification code is:").contains("123456")
                .doesNotContain("Hi ");
    }
}
