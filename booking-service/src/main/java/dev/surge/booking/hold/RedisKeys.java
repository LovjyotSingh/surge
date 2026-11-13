package dev.surge.booking.hold;

/** Single source of truth for Redis key and channel names. */
public final class RedisKeys {

    public static final String HOLD_EXPIRY = "holds:expiry";
    public static final String LOADTEST_LATEST = "loadtest:latest";

    private RedisKeys() {
    }

    public static String seat(long eventId, long seatId) {
        return "seat:" + eventId + ":" + seatId;
    }

    public static String sold(long eventId) {
        return "sold:" + eventId;
    }

    public static String hold(String holdId) {
        return "hold:" + holdId;
    }

    public static String userHold(long eventId, String userId) {
        return "userhold:" + eventId + ":" + userId;
    }

    public static String seatChannel(long eventId) {
        return "seats:" + eventId;
    }

    public static String idempotency(String userId, String key) {
        return "idem:" + userId + ":" + key;
    }

    public static String holdRate(String userId) {
        return "rate:holds:" + userId;
    }
}
