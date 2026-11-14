package dev.surge.auth.admin;

import dev.surge.auth.api.UserView;
import dev.surge.auth.api.UserViews;
import dev.surge.auth.error.ForbiddenException;
import dev.surge.auth.jwt.TokenService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Load-test support (D3). Not even registered unless surge.auth.test-tokens.enabled=true, so the route is a 404. */
@RestController
@RequestMapping("/api/auth/admin")
@ConditionalOnProperty(name = "surge.auth.test-tokens.enabled", havingValue = "true")
public class TestTokenController {
    public record MintedToken(String userId, String token) {
    }

    public record MintedTokens(List<MintedToken> tokens) {
    }

    private final TokenService tokens;

    public TestTokenController(TokenService tokens) {
        this.tokens = tokens;
    }

    @PostMapping("/test-tokens")
    MintedTokens mint(@RequestHeader(value = "X-User-Roles", required = false) String roles,
                      @Valid @RequestBody TestTokenRequest req) {
        if (roles == null || Arrays.stream(roles.split(",")).map(String::trim).noneMatch("ADMIN"::equals)) {
            throw new ForbiddenException();
        }
        List<MintedToken> out = new ArrayList<>(req.count());
        for (int n = 0; n < req.count(); n++) {
            UserView user = UserViews.synthetic(req.prefix() + "-" + n);
            out.add(new MintedToken(user.id(), tokens.issue(user).token()));
        }
        return new MintedTokens(out);
    }
}
