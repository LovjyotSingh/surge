package dev.surge.common.security;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code surge.internal-identity.*}. Locations are {@code file:}, {@code classpath:} or a plain file path; blank means
 * unset (the development keys in {@code dev}, a startup failure in {@code prod}).
 */
@ConfigurationProperties("surge.internal-identity")
public class InternalIdentityProperties {

    /** Turns on the servlet security chain that requires a verified {@code X-Surge-Identity} on every request. */
    private boolean enforce = false;

    /** Public JWKS holding every issuer's verification key(s); needed when {@link #enforce} is on. */
    private String jwksLocation;

    /** This service's private JWK; needed only when it signs ({@link #issuer} set). */
    private String signingKeyLocation;

    /** The name this service signs as ({@code gateway}, {@code svc:booking}, ...); unset = this service never signs. */
    private String issuer;

    /** Issuers whose tokens this service accepts. */
    private Set<String> allowedIssuers = new LinkedHashSet<>(InternalTokens.KNOWN_ISSUERS);

    public boolean isEnforce() {
        return enforce;
    }

    public void setEnforce(boolean enforce) {
        this.enforce = enforce;
    }

    public String getJwksLocation() {
        return jwksLocation;
    }

    public void setJwksLocation(String jwksLocation) {
        this.jwksLocation = jwksLocation;
    }

    public String getSigningKeyLocation() {
        return signingKeyLocation;
    }

    public void setSigningKeyLocation(String signingKeyLocation) {
        this.signingKeyLocation = signingKeyLocation;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public Set<String> getAllowedIssuers() {
        return allowedIssuers;
    }

    public void setAllowedIssuers(Set<String> allowedIssuers) {
        this.allowedIssuers = allowedIssuers;
    }
}
