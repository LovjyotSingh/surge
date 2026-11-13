package dev.surge.booking.hold;

import dev.surge.booking.error.RateLimitedException;
import dev.surge.booking.ratelimit.RateDecision;
import dev.surge.booking.ratelimit.RateLimitProperties;
import dev.surge.booking.ratelimit.RateLimiter;
import dev.surge.booking.web.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings/holds")
public class HoldController {

    private final HoldService service;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties.Bucket holdLimit;

    public HoldController(HoldService service, RateLimiter rateLimiter, RateLimitProperties props) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.holdLimit = props.holds();
    }

    /** {@code @CurrentUser} is declared before the body so a missing user (401) wins over body validation (400). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse hold(@CurrentUser String userId, @Valid @RequestBody HoldRequest request) {
        // Consume before the service call: rejected holds still cost a token.
        RateDecision d = rateLimiter.tryConsume(RedisKeys.holdRate(userId), holdLimit.capacity(),
                holdLimit.refillPerSecond());
        if (!d.allowed()) {
            throw new RateLimitedException(d.retryAfterSeconds());
        }
        return service.hold(userId, request.eventId(), request.seatIds());
    }
}
