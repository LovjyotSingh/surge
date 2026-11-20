package dev.surge.smoke.checks;

import static dev.surge.smoke.SmokeContext.expect;

import dev.surge.smoke.Api;
import dev.surge.smoke.Check;
import dev.surge.smoke.CheckFailure;
import dev.surge.smoke.SmokeContext;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Sign up a fresh user, verify the email with the code Mailpit received, log in, read /api/auth/me. */
public final class AuthFlowCheck implements Check {

    private static final Pattern CODE = Pattern.compile("(?m)^\\s+([0-9]{6})\\s*$");

    private final Duration mailWait;
    private final Duration interval;

    public AuthFlowCheck() {
        this(Duration.ofSeconds(30), Duration.ofSeconds(1));
    }

    public AuthFlowCheck(Duration mailWait, Duration interval) {
        this.mailWait = mailWait;
        this.interval = interval;
    }

    @Override
    public String name() {
        return "signup-verify-login";
    }

    @Override
    public String run(SmokeContext ctx) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "smoke-" + suffix + "@example.com";
        String password = "Smoke-" + UUID.randomUUID().toString().substring(0, 12);

        Api.Resp signup = expect(ctx.api().post("/api/auth/signup", null, Api.JSON.writeValueAsString(
            Map.of("email", email, "password", password, "displayName", "Smoke Tester"))), 201, "signup");
        String userId = signup.json().path("user").path("id").asString("?");
        String signupToken = signup.json().path("token").asString("");
        ctx.email = email;
        ctx.password = password;

        JsonNode message = Mailbox.waitForMessage(ctx, email, "the verification email", mailWait, interval,
            m -> true);
        Matcher code = CODE.matcher(message.path("Text").asString(""));
        if (!code.find()) {
            throw new CheckFailure("the verification email has no 6-digit code");
        }
        expect(ctx.api().post("/api/auth/verify", signupToken, "{\"code\":\"" + code.group(1) + "\"}"), 200,
            "verify email");

        String token = ctx.login(email, password);
        Api.Resp me = expect(ctx.api().get("/api/auth/me", token), 200, "GET /api/auth/me");
        if (!me.json().path("emailVerified").asBoolean(false)) {
            throw new CheckFailure("/api/auth/me says emailVerified=false after the code was accepted");
        }
        if (!email.equalsIgnoreCase(me.json().path("email").asString(""))) {
            throw new CheckFailure("/api/auth/me answers for a different email than the one signed up");
        }
        ctx.userToken = token;
        return "user " + userId + " signed up, verified by email code, logged in, /me ok";
    }
}
