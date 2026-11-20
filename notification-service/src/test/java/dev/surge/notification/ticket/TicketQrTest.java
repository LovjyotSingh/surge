package dev.surge.notification.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.surge.common.security.SurgeEnv;
import dev.surge.notification.config.TicketQrConfig;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * SEC-20: the ticket QR is {@code orderId.userId.<base64url HMAC-SHA256(key, orderId|userId|eventId)>}, so nobody
 * without the key can print a ticket that scans as valid, and a scanner can check it offline with the key.
 */
class TicketQrTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef-test".getBytes(StandardCharsets.UTF_8);
    private static final String KEY_BASE64 = Base64.getEncoder().encodeToString(KEY);

    private final TicketQr qr = TicketQr.fromConfig(KEY_BASE64, SurgeEnv.PROD);

    @Test
    void thePayloadIsOrderIdUserIdAndTheMacOfOrderUserAndEvent() throws Exception {
        String payload = qr.payload(1234L, "u-7", 3L);

        assertThat(payload).matches("^1234\\.u-7\\.[A-Za-z0-9_-]{43}$");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        String expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal("1234|u-7|3".getBytes(StandardCharsets.UTF_8)));
        assertThat(payload).isEqualTo("1234.u-7." + expected);
        assertThat(qr.payload(1234L, "u-7", 3L)).isEqualTo(payload);
    }

    @Test
    void itVerifies() {
        assertThat(qr.verify(qr.payload(1234L, "u-7", 3L), 3L)).isTrue();
        assertThat(qr.verify(qr.payload(1L, "lt-" + "a".repeat(61), Long.MAX_VALUE), Long.MAX_VALUE)).isTrue();
    }

    @Test
    void aTamperedPayloadFails() {
        String payload = qr.payload(1234L, "u-7", 3L);
        String mac = payload.substring(payload.lastIndexOf('.') + 1);
        for (String tampered : new String[] {
                "1235.u-7." + mac, "1234.u-8." + mac, "1234.U-7." + mac,
                payload.substring(0, payload.length() - 1) + (payload.endsWith("A") ? "B" : "A"),
                payload + "A", payload.substring(0, payload.length() - 1), "1234.u-7.", "1234.u-7", "1234..u-7." + mac,
                "01234.u-7." + mac, "+1234.u-7." + mac, "1234.u-7." + mac + ".x", "1234.u-7." + mac + "=",
                "1234.u 7." + mac, "", ".", "..", "surge:ticket:1234:u-7", "x".repeat(10_000)}) {
            assertThat(qr.verify(tampered, 3L)).as(tampered).isFalse();
        }
        assertThat(qr.verify(null, 3L)).isFalse();
    }

    /** The last of 43 base64 characters carries 2 unused bits: a second spelling of the same MAC must not verify. */
    @Test
    void onlyTheCanonicalSpellingOfTheMacVerifies() {
        String payload = qr.payload(1234L, "u-7", 3L);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        char last = payload.charAt(payload.length() - 1);
        String alias = payload.substring(0, payload.length() - 1) + alphabet.charAt(alphabet.indexOf(last) ^ 1);
        String mac = payload.substring(payload.lastIndexOf('.') + 1);
        String aliasMac = alias.substring(alias.lastIndexOf('.') + 1);

        assertThat(Base64.getUrlDecoder().decode(aliasMac)).isEqualTo(Base64.getUrlDecoder().decode(mac));
        assertThat(qr.verify(alias, 3L)).isFalse();
    }

    @Test
    void aTicketForAnotherEventFails() {
        assertThat(qr.verify(qr.payload(1234L, "u-7", 3L), 4L)).isFalse();
    }

    @Test
    void anotherKeyFails() {
        TicketQr other = TicketQr.fromConfig(Base64.getEncoder().encodeToString(
                "another-key-another-key-another-key!".getBytes(StandardCharsets.UTF_8)), SurgeEnv.PROD);
        assertThat(other.verify(qr.payload(1234L, "u-7", 3L), 3L)).isFalse();
    }

    @Test
    void devWithoutAKeyUsesTheDevKeyButProdRefusesBothMissingAndDevKeys() {
        TicketQr dev = TicketQr.fromConfig("", SurgeEnv.DEV);
        assertThat(dev.verify(dev.payload(1L, "u-1", 1L), 1L)).isTrue();
        assertThat(TicketQr.fromConfig(null, SurgeEnv.DEV).payload(1L, "u-1", 1L)).isEqualTo(dev.payload(1L, "u-1", 1L));

        for (String missing : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> TicketQr.fromConfig(missing, SurgeEnv.PROD))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("SURGE_TICKET_QR_KEY");
        }
        assertThatThrownBy(() -> TicketQr.fromConfig(TicketQr.DEV_KEY, SurgeEnv.PROD))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("development key");
        // the dev key is what dev uses when nothing is set
        assertThat(TicketQr.fromConfig(TicketQr.DEV_KEY, SurgeEnv.DEV).payload(1L, "u-1", 1L))
                .isEqualTo(dev.payload(1L, "u-1", 1L));
    }

    @Test
    void aKeyMustBeBase64OfAtLeast32Bytes() {
        String short31 = Base64.getEncoder().encodeToString(new byte[31]);
        for (SurgeEnv env : SurgeEnv.values()) {
            assertThatThrownBy(() -> TicketQr.fromConfig(short31, env)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least 32 bytes").hasMessageNotContaining(short31);
            assertThatThrownBy(() -> TicketQr.fromConfig("not base64!", env)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("base64").hasMessageNotContaining("not base64!");
        }
        assertThat(TicketQr.fromConfig(Base64.getEncoder().encodeToString(new byte[32]), SurgeEnv.PROD)).isNotNull();
    }

    /** The application refuses to start in prod without a real key; dev starts with the development key. */
    @Test
    void startupFollowsTheSameRules() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(TicketQrConfig.class);
        runner.withPropertyValues("surge.env=dev").run(ctx -> assertThat(ctx).hasSingleBean(TicketQr.class));
        runner.withPropertyValues("surge.env=prod").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("surge.env=prod", "surge.ticket.qr-key=" + TicketQr.DEV_KEY)
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("surge.env=prod", "surge.ticket.qr-key=" + KEY_BASE64)
                .run(ctx -> assertThat(ctx).hasSingleBean(TicketQr.class));
    }

    /** The longest payload (a 64-character user id) still scans at the size the PDF renders it. */
    @Test
    void theLongestPayloadStillScans() throws Exception {
        String payload = qr.payload(Long.MAX_VALUE, "x".repeat(64), 1L);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(QrCodes.png(payload, TicketPdfRenderer.QR_PIXELS)));
        assertThat(QrCodesTest.decode(image)).isEqualTo(payload);
    }
}
