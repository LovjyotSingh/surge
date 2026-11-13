package dev.surge.booking.hold;

import dev.surge.booking.error.MissingUserException;
import dev.surge.booking.error.RateLimitedException;
import dev.surge.booking.ratelimit.RateDecision;
import dev.surge.booking.ratelimit.RateLimitProperties;
import dev.surge.booking.ratelimit.RateLimiter;
import jakarta.validation.Valid;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings/holds")
public class HoldController {

    static final Pattern USER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final HoldService service;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties.Bucket holdLimit;

    public HoldController(HoldService service, RateLimiter rateLimiter, RateLimitProperties props) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.holdLimit = props.holds();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse hold(@RequestHeader(name = "X-User-Id", required = false) String userId,
                             @Valid @RequestBody HoldRequest request) {
        if (userId == null || !USER_ID.matcher(userId).matches()) {
            throw new MissingUserException();
        }
        // Consume before the service call: rejected holds still cost a token.
        RateDecision d = rateLimiter.tryConsume(RedisKeys.holdRate(userId), holdLimit.capacity(),
                holdLimit.refillPerSecond());
        if (!d.allowed()) {
            throw new RateLimitedException(d.retryAfterSeconds());
        }
        return service.hold(userId, request.eventId(), request.seatIds());
    }
}
