package dev.surge.auth.admin;

import dev.surge.auth.api.UserView;
import dev.surge.auth.api.UserViews;
import dev.surge.auth.error.InvalidBodyException;
import dev.surge.auth.jwt.TokenService;
import dev.surge.common.security.AdminAudit;
import dev.surge.common.security.CurrentIdentity;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Load-test support (D3). Not even registered unless surge.auth.test-tokens.enabled=true, so the route is a 404.
 *
 * <p>Only a gateway ADMIN reaches it: the identity chain checks that first ({@code AccessRulesConfig}), and the
 * method repeats it through {@link CurrentIdentity} as a second layer. The body is taken as raw text and
 * parsed/validated by hand AFTER that check: with {@code @Valid @RequestBody} Spring would reject a bad body with 400
 * before the method (and so the role check) ever ran, telling a non-admin about the request format. This keeps the
 * authorization decision first. A mint writes one {@code ADMIN_ACTION} audit line with the prefix and the count
 * ({@link AdminAudit}); a refusal by this check writes one {@code ADMIN_ACTION_REFUSED} line.
 */
@RestController
@RequestMapping("/api/auth/admin")
@ConditionalOnProperty(name = "surge.auth.test-tokens.enabled", havingValue = "true")
public class TestTokenController {
    public record MintedToken(String userId, String token) {
    }

    public record MintedTokens(List<MintedToken> tokens) {
    }

    private final TokenService tokens;
    private final JsonMapper json;
    private final Validator validator;

    public TestTokenController(TokenService tokens, JsonMapper json, Validator validator) {
        this.tokens = tokens;
        this.json = json;
        this.validator = validator;
    }

    @PostMapping("/test-tokens")
    MintedTokens mint(@RequestBody(required = false) String rawBody) {
        AdminAudit.requireAdmin("auth.test-tokens.mint");
        TestTokenRequest req = parse(rawBody);
        List<MintedToken> out = new ArrayList<>(req.count());
        for (int n = 0; n < req.count(); n++) {
            UserView user = UserViews.synthetic(req.prefix() + "-" + n);
            out.add(new MintedToken(user.id(), tokens.issueLoadTest(user).token()));
        }
        // the validated prefix and the count only: never a token
        AdminAudit.log("auth.test-tokens.mint", "prefix:" + req.prefix() + ",count:" + req.count());
        return new MintedTokens(out);
    }

    private TestTokenRequest parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new InvalidBodyException(null);
        }
        TestTokenRequest req;
        try {
            req = json.readValue(rawBody, TestTokenRequest.class);
        } catch (JacksonException e) {
            throw new InvalidBodyException(null);
        }
        if (req == null) {
            throw new InvalidBodyException(null);
        }
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<TestTokenRequest> v : validator.validate(req)) {
            errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage());
        }
        if (!errors.isEmpty()) {
            throw new InvalidBodyException(errors);
        }
        return req;
    }
}
