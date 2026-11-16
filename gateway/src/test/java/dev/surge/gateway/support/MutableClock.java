package dev.surge.gateway.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** The real time plus an offset that tests push forward by hand; it keeps ticking in between. */
public final class MutableClock extends Clock {

    private volatile Duration offset = Duration.ZERO;

    public synchronized void advance(Duration by) {
        offset = offset.plus(by);
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
