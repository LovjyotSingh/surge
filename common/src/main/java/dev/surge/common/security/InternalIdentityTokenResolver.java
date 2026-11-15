package dev.surge.common.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

/**
 * Reads the token from {@value InternalTokens#HEADER} (the raw compact JWS, no {@code Bearer} prefix) and from
 * nowhere else: {@code Authorization}, query parameters and form fields are ignored. No header means "no token"
 * (anonymous, so 401 unless the path is open); an empty, repeated or malformed header is an invalid token (401).
 */
public final class InternalIdentityTokenResolver implements BearerTokenResolver {

    @Override
    public String resolve(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(InternalTokens.HEADER);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String token = values.nextElement();
        if (values.hasMoreElements()) {
            throw new InvalidBearerTokenException("More than one identity token");
        }
        if (token == null || token.length() > InternalTokens.MAX_TOKEN_LENGTH
                || !InternalTokens.COMPACT_JWS.matcher(token).matches()) {
            throw new InvalidBearerTokenException("Malformed identity token");
        }
        return token;
    }
}
