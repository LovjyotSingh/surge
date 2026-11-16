package dev.surge.notification.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import dev.surge.common.security.InternalIdentity;
import dev.surge.common.security.InternalKeys;
import dev.surge.common.security.InternalTokenVerifier;
import dev.surge.common.security.SurgeEnv;
import java.util.Set;
import mockwebserver3.RecordedRequest;
import org.springframework.core.io.DefaultResourceLoader;

/** What every call notification makes must carry, checked the way the callee checks it (the committed DEV keys). */
public final class SignedRequests {

    private static final JWKSet DEV_JWKS = InternalKeys.loadVerificationKeys(null, SurgeEnv.DEV,
            new DefaultResourceLoader());

    private SignedRequests() {
    }

    /**
     * Exactly one {@code X-Surge-Identity}, valid for {@code audience} only, signed as {@code svc:notification}
     * speaking for itself (no user, no roles), and no {@code X-User-*} impersonation header.
     */
    public static InternalIdentity assertSignedFor(RecordedRequest request, String audience) {
        assertThat(request.getHeaders().values("X-Surge-Identity")).hasSize(1);
        InternalIdentity identity = new InternalTokenVerifier(DEV_JWKS, Set.of("svc:notification"), audience)
                .verify(request.getHeaders().get("X-Surge-Identity"));
        assertThat(identity.issuer()).isEqualTo("svc:notification");
        assertThat(identity.subject()).isEqualTo("svc:notification");
        assertThat(identity.roles()).isEmpty();
        for (String legacy : new String[] {"X-User-Id", "X-User-Roles", "X-User-Email"}) {
            assertThat(request.getHeaders().get(legacy)).as(legacy).isNull();
        }
        return identity;
    }
}
