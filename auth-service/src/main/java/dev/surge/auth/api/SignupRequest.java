package dev.surge.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) @Utf8MaxBytes(72) String password,
        @NotBlank @Size(max = DISPLAY_NAME_MAX, message = DISPLAY_NAME_MESSAGE)
        @Pattern(regexp = DISPLAY_NAME_PATTERN, message = DISPLAY_NAME_MESSAGE) String displayName) {

    static final int DISPLAY_NAME_MAX = 60;
    static final String DISPLAY_NAME_MESSAGE =
            "Use 1 to 60 letters, digits, spaces and . ' - only, and no web addresses";
    /**
     * Letters (any script), digits, space and {@code . ' -}; the lookahead refuses a dot that joins two words into
     * something address-shaped ({@code evil.com}) while keeping initials such as {@code J.R. Smith}. The name is
     * printed in the web app, so it carries no text that could pass for a link or a message.
     */
    static final String DISPLAY_NAME_PATTERN =
            "^(?!.*[\\p{L}\\p{N}]\\.\\p{L}{2})[\\p{L}\\p{N} .'\\-]*$";

    @Override
    public String toString() {
        return "SignupRequest[email=" + email + ", password=***, displayName=" + displayName + "]";
    }
}
