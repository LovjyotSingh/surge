package dev.surge.auth.api;

import com.nimbusds.jose.jwk.JWKSet;
import dev.surge.auth.error.MissingUserException;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public static final Pattern USER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final AuthService auth;
    private final UserLookup lookup;
    private final JWKSet publicJwkSet;

    AuthController(AuthService auth, UserLookup lookup, JWKSet publicJwkSet) {
        this.auth = auth;
        this.lookup = lookup;
        this.publicJwkSet = publicJwkSet;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    TokenResponse signup(@Valid @RequestBody SignupRequest req) {
        return auth.signup(req);
    }

    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req);
    }

    @GetMapping("/me")
    UserView me(@RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || !USER_ID.matcher(userId).matches()) {
            throw new MissingUserException();
        }
        return lookup.find(userId);
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = "application/json")
    Map<String, Object> jwks() {
        return publicJwkSet.toJSONObject(true);
    }
}
