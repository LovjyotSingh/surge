package dev.surge.common.security;

import com.nimbusds.jwt.JWTClaimsSet;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * {@link InternalTokenVerifier} as a Spring Security {@link JwtDecoder}. Every rejection is a {@link BadJwtException},
 * which the resource server turns into a 401. Never call {@link Jwt#getIssuer()} on the result: {@code iss} is a plain
 * name such as {@code gateway}, not a URL; read {@code getClaimAsString("iss")}.
 */
public final class InternalJwtDecoder implements JwtDecoder {

    private final InternalTokenVerifier verifier;

    public InternalJwtDecoder(InternalTokenVerifier verifier) {
        this.verifier = verifier;
    }

    public InternalTokenVerifier verifier() {
        return verifier;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        InternalTokenVerifier.Verified verified;
        try {
            verified = verifier.verifyToken(token);
        } catch (InvalidInternalTokenException e) {
            throw new BadJwtException(e.getMessage());
        }
        JWTClaimsSet claims = verified.claims();
        Map<String, Object> values = new LinkedHashMap<>(claims.getClaims());
        values.replaceAll((name, value) -> value instanceof Date date ? date.toInstant() : value);
        values.put("aud", List.copyOf(claims.getAudience()));
        values.put(InternalTokens.ROLES, List.copyOf(verified.identity().roles()));
        return Jwt.withTokenValue(token)
                .headers(headers -> headers.putAll(verified.jwt().getHeader().toJSONObject()))
                .claims(all -> all.putAll(values))
                .build();
    }
}
