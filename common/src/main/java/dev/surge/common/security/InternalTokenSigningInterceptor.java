package dev.surge.common.security;

import dev.surge.common.web.RequestIdFilter;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Signs every outgoing service-to-service call: sets {@value InternalTokens#HEADER} to a fresh token from
 * {@link InternalTokenSigner#signForService} ({@code iss} = {@code sub} = this service, no roles), addressed to the
 * one {@code audience} this client talks to. The audience is fixed when the client is built
 * ({@link InternalAudiences#forClient}); nothing about the request (URL, host, headers) can change it.
 *
 * <ul>
 *   <li>A token is signed per HTTP attempt, so a retried call never reuses an old one, and nothing is cached.</li>
 *   <li>Whatever the request already carried in {@value InternalTokens#HEADER} is replaced: a token this service
 *       received is never forwarded.</li>
 *   <li>The current request id (MDC, set by {@link RequestIdFilter}) goes into the token's {@code rid} and, when the
 *       call has none yet, into {@value RequestIdFilter#HEADER}, so the callee logs the same id. A malformed id is
 *       dropped, never forwarded.</li>
 * </ul>
 */
public final class InternalTokenSigningInterceptor implements ClientHttpRequestInterceptor {

    private final InternalTokenSigner signer;
    private final String audience;

    public InternalTokenSigningInterceptor(InternalTokenSigner signer, String audience) {
        if (signer == null || !InternalTokens.isServiceIssuer(signer.issuer())) {
            throw new IllegalArgumentException("Service-to-service calls are signed with a service key (svc:*)");
        }
        if (!InternalAudiences.KNOWN.contains(audience)) {
            throw new IllegalArgumentException("Unknown audience '" + audience + "'");
        }
        this.signer = signer;
        this.audience = audience;
    }

    public String audience() {
        return audience;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        boolean validRequestId = requestId != null && InternalTokens.REQUEST_ID_VALUE.matcher(requestId).matches();
        HttpHeaders headers = request.getHeaders();
        headers.set(InternalTokens.HEADER, signer.signForService(audience, validRequestId ? requestId : null));
        if (validRequestId && headers.getFirst(RequestIdFilter.HEADER) == null) {
            headers.set(RequestIdFilter.HEADER, requestId);
        }
        return execution.execute(request, body);
    }
}
