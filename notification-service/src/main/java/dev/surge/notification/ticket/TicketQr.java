package dev.surge.notification.ticket;

import dev.surge.common.security.SurgeEnv;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The signed ticket QR (SEC-20):
 *
 * <pre>orderId.userId.&lt;base64url, no padding, of HMAC-SHA256(key, "orderId|userId|eventId")&gt;</pre>
 *
 * Order ids are sequential and user ids short, so the old {@code surge:ticket:<orderId>:<userId>} could be printed by
 * anyone. Now only the holder of {@code SURGE_TICKET_QR_KEY} can make a payload that {@link #verify}s, and the event id
 * is bound in without being printed: a ticket for one event does not scan at another. A venue scanner needs only this
 * class and the key (no database). User ids never contain a dot ({@code [A-Za-z0-9_-]}), so the three parts split
 * unambiguously.
 *
 * <p>The key ({@code surge.ticket.qr-key}, env {@code SURGE_TICKET_QR_KEY}) is base64 of at least 32 random bytes
 * ({@code openssl rand -base64 32}). Unset in dev: {@link #DEV_KEY}, with a warning. In prod, unset or the dev key:
 * startup fails. The key and MACs are never logged.
 */
public final class TicketQr {

    /** HMAC-SHA256 keys shorter than the hash add no strength and are refused. */
    public static final int MIN_KEY_BYTES = 32;
    /**
     * DEV ONLY, committed on purpose so a local run signs tickets without setup. Anyone can read it, so
     * {@code SURGE_ENV=prod} refuses it.
     */
    public static final String DEV_KEY = Base64.getEncoder().encodeToString(
            "surge-dev-only-ticket-qr-key-not-secret".getBytes(StandardCharsets.UTF_8));

    private static final Logger log = LoggerFactory.getLogger(TicketQr.class);
    private static final String ALGORITHM = "HmacSHA256";
    private static final Pattern ORDER_ID = Pattern.compile("^[1-9][0-9]{0,18}$");
    private static final Pattern USER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    /** 32 MAC bytes in base64url without padding. */
    private static final Pattern MAC = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    /** Longer than any valid payload (19 + 1 + 64 + 1 + 43); anything longer is refused before any work. */
    private static final int MAX_PAYLOAD = 160;

    private final SecretKeySpec key;

    private TicketQr(byte[] key) {
        this.key = new SecretKeySpec(key, ALGORITHM);
    }

    /**
     * The verifier for the configured key: {@code base64Key} blank means unset. Messages name the variable, never
     * the value.
     *
     * @throws IllegalStateException in prod without a key or with {@link #DEV_KEY}; anywhere for a key that is not
     *                               base64 or shorter than {@value #MIN_KEY_BYTES} bytes
     */
    public static TicketQr fromConfig(String base64Key, SurgeEnv env) {
        if (base64Key == null || base64Key.isBlank()) {
            if (env == SurgeEnv.PROD) {
                throw new IllegalStateException("SURGE_TICKET_QR_KEY is required when SURGE_ENV=prod (base64 of at "
                        + "least " + MIN_KEY_BYTES + " random bytes, e.g. openssl rand -base64 32)");
            }
            log.warn("SURGE_TICKET_QR_KEY is not set: ticket QR codes are signed with the committed DEV ONLY key. "
                    + "Never use this outside local development; SURGE_ENV=prod refuses it.");
            return new TicketQr(Base64.getDecoder().decode(DEV_KEY));
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("SURGE_TICKET_QR_KEY must be base64 (standard alphabet)");
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("SURGE_TICKET_QR_KEY must decode to at least " + MIN_KEY_BYTES + " bytes");
        }
        if (env == SurgeEnv.PROD && MessageDigest.isEqual(key, Base64.getDecoder().decode(DEV_KEY))) {
            throw new IllegalStateException("SURGE_TICKET_QR_KEY is the committed development key; SURGE_ENV=prod "
                    + "refuses it");
        }
        return new TicketQr(key);
    }

    /** The QR text for this ticket. {@code userId} must be a user id ({@code [A-Za-z0-9_-]{1,64}}). */
    public String payload(long orderId, String userId, long eventId) {
        if (orderId <= 0 || userId == null || !USER_ID.matcher(userId).matches()) {
            throw new IllegalArgumentException("Not a ticket: order " + orderId);
        }
        return orderId + "." + userId + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac(orderId, userId, eventId));
    }

    /**
     * True only for a payload this key made for a ticket to event {@code eventId}, in its exact canonical form. The
     * encoded MAC is compared in constant time ({@link MessageDigest#isEqual}), as text: base64 decoders ignore the
     * unused low bits of the last character, so comparing decoded bytes would accept more than one spelling.
     */
    public boolean verify(String payload, long eventId) {
        if (payload == null || payload.length() > MAX_PAYLOAD) {
            return false;
        }
        String[] parts = payload.split("\\.", -1);
        if (parts.length != 3 || !ORDER_ID.matcher(parts[0]).matches() || !USER_ID.matcher(parts[1]).matches()
                || !MAC.matcher(parts[2]).matches()) {
            return false;
        }
        long orderId;
        try {
            orderId = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return false;
        }
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(mac(orderId, parts[1], eventId));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                parts[2].getBytes(StandardCharsets.US_ASCII));
    }

    private byte[] mac(long orderId, String userId, long eventId) {
        try {
            // Mac is not thread-safe: one per call (renders run on several threads)
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal((orderId + "|" + userId + "|" + eventId).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
