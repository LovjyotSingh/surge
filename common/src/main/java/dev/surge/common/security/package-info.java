/**
 * Zero-trust internal identity (security audit section 3, SEC-03).
 *
 * <p>The gateway signs an {@code X-Surge-Identity} token for every request it routes, and services sign their own
 * for service-to-service calls. A service with {@code surge.internal-identity.enforce=true} accepts a request only
 * with a valid token addressed to it, and never reads {@code Authorization} or {@code X-User-*}.
 *
 * <ul>
 *   <li>{@link dev.surge.common.security.InternalTokenSigner} and {@link dev.surge.common.security.InternalTokenVerifier}:
 *       the token itself (ES256, {@code typ surge-id+jwt}, {@code kid} = {@code iss}, one audience, 60 s).</li>
 *   <li>{@link dev.surge.common.security.InternalSecurityAutoConfiguration}: the servlet chain;
 *       {@link dev.surge.common.security.SurgeAccessRules} and {@link dev.surge.common.security.InternalAccess} for
 *       per-service rules; {@link dev.surge.common.security.CurrentIdentity} for controllers.</li>
 *   <li>{@link dev.surge.common.security.InternalKeys}: key loading, the committed development keys, and the
 *       production refusals.</li>
 * </ul>
 *
 * <h2>Who can say what</h2>
 * Only the gateway states users: a gateway token's {@code sub} is a user id (or absent: anonymous) and its
 * {@code roles} are that user's. A service token ({@code svc:*}) states only the service: {@code sub} equals its
 * {@code iss} and it has no roles. A compromised service can therefore call another service as itself, where the
 * callee allows it ({@code allowed-issuers}, default gateway only, plus {@code InternalAccess.service}), but it can
 * never act as a user or an admin.
 *
 * <h2>Services</h2>
 * Every servlet service (auth, booking, catalog, payment, notification) sets {@code enforce=true}, lists its
 * {@code allowed-issuers}, and declares one {@link dev.surge.common.security.SurgeAccessRules} bean that mirrors the
 * gateway's rules for its paths. Controllers read the caller only through
 * {@link dev.surge.common.security.CurrentIdentity}; nothing reads {@code X-User-*}, and the gateway no longer sends
 * them. The chain denies by default: a path or method no rule names is refused, whoever asks. With
 * {@code SURGE_ENV=prod} a servlet service refuses to start unless {@code enforce=true}
 * ({@code CommonAutoConfiguration}). Tests sign tokens with {@code IdentityTokens} from common's test-jar.
 *
 * <h2>Service-to-service calls</h2>
 * booking (to payment) and notification (to booking, auth and catalog) sign every call with their own key through
 * {@link dev.surge.common.security.InternalTokenSigningInterceptor}: a fresh {@code svc:*} token per HTTP attempt,
 * addressed to the one service the client talks to ({@link dev.surge.common.security.InternalAudiences#forClient},
 * fixed at startup, never taken from a request). Each callee trusts exactly the issuers it needs and names the
 * endpoints they may use ({@code InternalAccess.service}); internal controllers check again with
 * {@code CurrentIdentity.requireService}.
 *
 * <h2>Actuator</h2>
 * Only {@code /actuator/health} and {@code /actuator/health/**} are open (container health checks; status only).
 * Every other actuator endpoint, {@code /actuator/info} and {@code /actuator/prometheus} included, is denied to every
 * caller, token or not. Phase 3 (observability) adds a scrape identity or a separate management port.
 *
 * <h2>Replay</h2>
 * {@code jti} is not tracked. A captured token is accepted again by design, but only within its 60 s lifetime and
 * only by the one service it is addressed to. If a replay cache is ever added, it must key on {@code jti} (unique per
 * token) and never on the token string: ECDSA signatures are malleable, so anyone holding a valid token can turn its
 * signature {@code (r, s)} into {@code (r, n - s)}, a different string that verifies just the same, without the key.
 * A cache keyed on the string would miss that copy.
 */
package dev.surge.common.security;
