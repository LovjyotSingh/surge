package dev.surge.smoke.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.surge.smoke.CheckFailure;
import dev.surge.smoke.SmokeConfig;
import dev.surge.smoke.SmokeContext;
import dev.surge.smoke.StubServer;
import dev.surge.smoke.StubServer.Reply;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Every check against a scripted stub that plays the part of nginx and Mailpit. */
class ChecksTest {

    private static final Duration SHORT = Duration.ofMillis(300);
    private static final Duration TICK = Duration.ofMillis(20);

    private StubServer stub;
    private SmokeContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubServer().on("POST /api/auth/login", Reply.of(200, "{\"token\":\"adm\"}"));
        ctx = SmokeContext.of(new SmokeConfig(stub.baseUrl(), stub.baseUrl(), "admin@x", "pw", 1));
    }

    @AfterEach
    void tearDown() {
        ctx.close();
        stub.close();
    }

    private static String problem(String type) {
        return "{\"type\":\"https://surge.dev/problems/" + type + "\",\"status\":0}";
    }

    // 1 ---------------------------------------------------------------------------------------------------------

    private void stubAuthFlow(boolean verified) {
        AtomicReference<String> email = new AtomicReference<>("");
        stub.onBody("POST /api/auth/signup", body -> {
            email.set(body.replaceAll(".*\"email\":\"([^\"]+)\".*", "$1"));
            return Reply.of(201, "{\"token\":\"t-unverified\",\"user\":{\"id\":\"u-9\"}}");
        });
        stub.on("GET /api/v1/search", Reply.of(200, "{\"messages\":[{\"ID\":\"m1\",\"Subject\":\"Verify\"}]}"));
        stub.on("GET /api/v1/message/m1", Reply.of(200, "{\"Text\":\"Hi Smoke ab654321,\\r\\n\\r\\nYour Surge verification code is:\\r\\n\\r\\n    123456\\r\\n\\r\\n"
            + "It expires in 999999 minutes.\\r\\n\","
            + "\"Attachments\":[]}"));
        stub.on("POST /api/auth/verify", Reply.of(200, "{\"token\":\"t-verified\"}"));
        stub.on("POST /api/auth/login", Reply.of(200, "{\"token\":\"t-login\"}"));
        stub.onBody("GET /api/auth/me", b -> Reply.of(200, "{\"id\":\"u-9\",\"email\":\"" + email.get()
            + "\",\"emailVerified\":" + verified + "}"));
    }

    @Test
    void signupVerifyLoginMeHappyPath() throws Exception {
        stubAuthFlow(true);

        String detail = new AuthFlowCheck(SHORT, TICK).run(ctx);

        assertThat(detail).contains("u-9");
        assertThat(ctx.userToken).isEqualTo("t-login");
        assertThat(ctx.email).contains("@");
        assertThat(stub.received).contains("POST /api/auth/signup", "POST /api/auth/verify", "GET /api/auth/me");
        assertThat(stub.receivedBodies.get(stub.received.indexOf("POST /api/auth/verify"))).contains("123456");
    }

    @Test
    void signupFailsWhenMeSaysTheEmailIsStillUnverified() {
        stubAuthFlow(false);

        assertThatThrownBy(() -> new AuthFlowCheck(SHORT, TICK).run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("emailVerified");
    }

    @Test
    void signupFailsWhenNoEmailArrives() {
        stubAuthFlow(true);
        stub.on("GET /api/v1/search", Reply.of(200, "{\"messages\":[]}"));

        assertThatThrownBy(() -> new AuthFlowCheck(SHORT, TICK).run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("verification email");
    }

    @Test
    void signupFailureNamesTheStatus() {
        stub.on("POST /api/auth/signup", Reply.of(429, problem("rate-limited")));

        assertThatThrownBy(() -> new AuthFlowCheck(SHORT, TICK).run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("429").hasMessageContaining("rate-limited");
    }

    // 2 ---------------------------------------------------------------------------------------------------------

    @Test
    void fuzzySearchFindsColdplayAndTheCityFilterNarrowsIt() throws Exception {
        stub.on("GET /api/catalog/events/search", Reply.of(200,
            "{\"items\":[{\"id\":1,\"title\":\"Coldplay Tribute Live\",\"city\":\"Mumbai\"}],\"total\":1}"));

        String detail = new SearchCheck().run(ctx);

        assertThat(detail).contains("Coldplay Tribute Live");
        assertThat(stub.receivedQueries.get(0)).contains("q=coldply");
        assertThat(stub.receivedQueries.get(1)).contains("city=Mumbai");
    }

    @Test
    void searchFailsWhenTheTitleIsMissing() {
        stub.on("GET /api/catalog/events/search", Reply.of(200, "{\"items\":[],\"total\":0}"));

        assertThatThrownBy(() -> new SearchCheck().run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("Coldplay Tribute Live");
    }

    @Test
    void searchFailsWhenTheCityFilterLetsAnotherCityThrough() {
        stub.onBody("GET /api/catalog/events/search", b -> Reply.of(200,
            "{\"items\":[{\"title\":\"Coldplay Tribute Live\",\"city\":\"Mumbai\"},{\"title\":\"X\",\"city\":\"Delhi\"}]}"));

        assertThatThrownBy(() -> new SearchCheck().run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("Delhi");
    }

    // 3 ---------------------------------------------------------------------------------------------------------

    private void stubBooking(String orderTotal) {
        ctx.userToken = "user-jwt";
        stub.on("GET /api/catalog/events/1", Reply.of(200, "{\"id\":1,\"seatsLeft\":100,\"totalSeats\":100}"));
        stub.on("GET /api/bookings/events/1", Reply.of(200, "{\"seats\":[{\"id\":5,\"state\":\"AVAILABLE\"},"
            + "{\"id\":6,\"state\":\"AVAILABLE\"},{\"id\":7,\"state\":\"SOLD\"},{\"id\":8,\"state\":\"AVAILABLE\"}]}"));
        stub.on("POST /api/bookings/holds", Reply.of(201, "{\"holdId\":\"h-1\",\"quotedTotalInr\":2400.00}"));
        stub.on("POST /api/bookings/orders", Reply.of(201, "{\"orderId\":77,\"totalInr\":" + orderTotal + "}"));
    }

    @Test
    void holdTwoSeatsThenCheckoutChargesTheQuote() throws Exception {
        stubBooking("2400.0");

        String detail = new HoldCheckoutCheck().run(ctx);

        assertThat(detail).contains("77");
        assertThat(ctx.orderId).isEqualTo(77L);
        assertThat(ctx.seatsLeftBefore).isEqualTo(100);
        assertThat(stub.receivedBodies.get(stub.received.indexOf("POST /api/bookings/holds")))
            .contains("[5, 6]");
        assertThat(ctx.usedSeats).containsExactlyInAnyOrder(5L, 6L);
        assertThat(stub.receivedIdempotencyKeys.get(stub.received.indexOf("POST /api/bookings/orders")))
            .startsWith("smoke-");
    }

    @Test
    void anEventWithFreeSeatsIsNotResetFirst() throws Exception {
        stubBooking("2400");

        new HoldCheckoutCheck().run(ctx);

        assertThat(stub.received).doesNotContain("POST /api/bookings/admin/events/1/reset");
    }

    @Test
    void aSoldOutEventIsResetFirstAndThenBought() throws Exception {
        stubBooking("2400");
        AtomicBoolean reset = new AtomicBoolean();
        stub.onBody("POST /api/bookings/admin/events/1/reset", b -> {
            reset.set(true);
            return Reply.of(204, "");
        });
        stub.onBody("GET /api/bookings/events/1", b -> Reply.of(200, reset.get()
            ? "{\"seats\":[{\"id\":1,\"state\":\"AVAILABLE\"},{\"id\":2,\"state\":\"AVAILABLE\"},"
                + "{\"id\":3,\"state\":\"AVAILABLE\"}]}"
            : "{\"seats\":[{\"id\":1,\"state\":\"AVAILABLE\"},{\"id\":2,\"state\":\"SOLD\"}]}"));

        String detail = new HoldCheckoutCheck().run(ctx);

        assertThat(detail).contains("reset first");
        assertThat(stub.received.indexOf("POST /api/bookings/admin/events/1/reset"))
            .isLessThan(stub.received.indexOf("POST /api/bookings/holds"));
        assertThat(stub.receivedAuth.get(stub.received.indexOf("POST /api/bookings/admin/events/1/reset")))
            .isEqualTo("Bearer adm");
    }

    @Test
    void theBeforeCountWaitsUntilTheCatalogHasCaughtUpWithTheReset() throws Exception {
        stubBooking("2400");
        stub.on("GET /api/catalog/events/1",
            Reply.of(200, "{\"seatsLeft\":0,\"totalSeats\":100}"),
            Reply.of(200, "{\"seatsLeft\":40,\"totalSeats\":100}"),
            Reply.of(200, "{\"seatsLeft\":100,\"totalSeats\":100}"));

        new HoldCheckoutCheck(Duration.ofSeconds(5), TICK).run(ctx);

        assertThat(ctx.seatsLeftBefore).isEqualTo(100);
    }

    @Test
    void failsWhenTheCatalogNeverShowsAFreshEvent() {
        stubBooking("2400");
        stub.on("GET /api/catalog/events/1", Reply.of(200, "{\"seatsLeft\":90,\"totalSeats\":100}"));

        assertThatThrownBy(() -> new HoldCheckoutCheck(SHORT, TICK).run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("catalog").hasMessageContaining("seatsLeft=90 of 100");
    }

    @Test
    void checkoutFailsWhenTheAmountDiffersFromTheQuote() {
        stubBooking("2500.00");

        assertThatThrownBy(() -> new HoldCheckoutCheck().run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("2500").hasMessageContaining("2400");
    }

    @Test
    void holdFailureShowsTheProblemType() {
        stubBooking("2400");
        stub.on("POST /api/bookings/holds", Reply.of(403, problem("email-unverified")));

        assertThatThrownBy(() -> new HoldCheckoutCheck().run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("email-unverified");
    }

    @Test
    void checkoutNeedsTheSignupCheckFirst() {
        assertThatThrownBy(() -> new HoldCheckoutCheck().run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("signup");
    }

    // 4 ---------------------------------------------------------------------------------------------------------

    private void stubTicket(String pdf) {
        ctx.userToken = "user-jwt";
        ctx.email = "smoke-1@example.com";
        ctx.orderId = 77L;
        stub.on("GET /api/v1/search", Reply.of(200,
            "{\"messages\":[{\"ID\":\"m9\",\"Subject\":\"Your Surge tickets\",\"Attachments\":1}]}"));
        stub.on("GET /api/v1/message/m9", Reply.of(200, "{\"Attachments\":[{\"FileName\":\"ticket.pdf\","
            + "\"ContentType\":\"application/pdf\"}]}"));
        stub.on("GET /api/notifications/tickets/77.pdf", Reply.of(200, pdf));
    }

    @Test
    void ticketEmailWithPdfAndDownloadWork() throws Exception {
        stubTicket("%PDF-1.7 fake");

        assertThat(new TicketCheck(SHORT, TICK).run(ctx)).contains("pdf");
    }

    @Test
    void ticketFailsWhenTheDownloadIsNotAPdf() {
        stubTicket("<html>nope</html>");

        assertThatThrownBy(() -> new TicketCheck(SHORT, TICK).run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("%PDF");
    }

    @Test
    void ticketFailsWhenTheEmailHasNoPdfAttachment() {
        stubTicket("%PDF-1.7");
        stub.on("GET /api/v1/message/m9", Reply.of(200, "{\"Attachments\":[]}"));

        assertThatThrownBy(() -> new TicketCheck(SHORT, TICK).run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("PDF");
    }

    // 5 ---------------------------------------------------------------------------------------------------------

    private void stubCatalogAudit(int seatsLeftNow, String auditBody) {
        ctx.orderId = 77L;
        ctx.seatsLeftBefore = 100;
        stub.on("GET /api/catalog/events/1", Reply.of(200, "{\"seatsLeft\":" + seatsLeftNow + "}"));
        stub.on("GET /api/notifications/admin/audit", Reply.of(200, auditBody));
    }

    @Test
    void catalogSeatsLeftDropsByTwoAndTheAuditLogNamesTheOrder() throws Exception {
        stubCatalogAudit(98, "{\"date\":\"2026-11-20\",\"lines\":1,\"tail\":[{\"type\":\"order.confirmed\","
            + "\"payload\":\"{\\\"orderId\\\":77}\"}]}");

        assertThat(new CatalogAuditCheck(SHORT, TICK).run(ctx)).contains("98");
    }

    @Test
    void catalogFailsWhenSeatsLeftNeverDrops() {
        stubCatalogAudit(100, "{\"tail\":[{\"orderId\":77}]}");

        assertThatThrownBy(() -> new CatalogAuditCheck(SHORT, TICK).run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("seatsLeft");
    }

    @Test
    void auditFailsWhenTheOrderIsNotInTheLog() {
        stubCatalogAudit(98, "{\"tail\":[{\"orderId\":770}]}");

        assertThatThrownBy(() -> new CatalogAuditCheck(SHORT, TICK).run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("audit");
    }

    // 6 ---------------------------------------------------------------------------------------------------------

    private AtomicBoolean stubChaos(boolean recovers) {
        ctx.userToken = "user-jwt";
        AtomicBoolean on = new AtomicBoolean();
        AtomicBoolean held = new AtomicBoolean();
        // Seat 9 is the free one; it shows as HELD once the hold has been placed.
        stub.onBody("GET /api/bookings/events/1", b -> Reply.of(200, "{\"seats\":[{\"id\":5,\"state\":\"SOLD\"},"
            + "{\"id\":9,\"state\":\"" + (held.get() ? "HELD" : "AVAILABLE") + "\"}]}"));
        stub.onBody("PUT /api/payments/admin/chaos", body -> {
            on.set(!body.contains("\"errorRate\":0.0"));
            return Reply.of(204, "");
        });
        stub.onBody("POST /api/bookings/holds", b -> {
            held.set(true);
            return Reply.of(201, "{\"holdId\":\"h-2\",\"quotedTotalInr\":1200}");
        });
        // Like the real stack: calls that reach payment are slow; once the circuit is open (after 3 calls) they are not.
        AtomicInteger reachedPayment = new AtomicInteger();
        stub.onBody("POST /api/bookings/orders", b -> {
            if (!on.get() && recovers) {
                return Reply.of(201, "{\"orderId\":88,\"totalInr\":1200}");
            }
            if (on.get() && reachedPayment.incrementAndGet() <= 3) {
                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return Reply.of(503, problem("payment-unavailable"));
        });
        return on;
    }

    @Test
    void chaosOpensTheCircuitThenRecovers() throws Exception {
        stubChaos(true);

        String detail = new PaymentChaosCheck(6, Duration.ofMillis(50), Duration.ofSeconds(2), TICK, 50).run(ctx);

        assertThat(detail).contains("fast").contains("recovered");
        int puts = (int) stub.received.stream().filter("PUT /api/payments/admin/chaos"::equals).count();
        assertThat(puts).isEqualTo(2);
        assertThat(stub.receivedBodies.get(stub.received.lastIndexOf("PUT /api/payments/admin/chaos")))
            .contains("\"errorRate\":0.0");
    }

    @Test
    void chaosIsSetWithLatencyAndLatencyIsClearedAgain() throws Exception {
        stubChaos(true);

        new PaymentChaosCheck(6, Duration.ofMillis(50), Duration.ofSeconds(2), TICK, 50).run(ctx);

        int firstPut = stub.received.indexOf("PUT /api/payments/admin/chaos");
        int lastPut = stub.received.lastIndexOf("PUT /api/payments/admin/chaos");
        assertThat(stub.receivedBodies.get(firstPut)).contains("\"latencyMs\":500").contains("\"errorRate\":1.0");
        assertThat(stub.receivedBodies.get(lastPut)).contains("\"latencyMs\":0").contains("\"errorRate\":0.0");
    }

    @Test
    void chaosFailsWhenTheFirstFailingCheckoutIsNotSlow() {
        stubChaos(true);

        assertThatThrownBy(() -> new PaymentChaosCheck(6, Duration.ofMillis(50), Duration.ofSeconds(1), TICK, 5_000)
            .run(ctx)).isInstanceOf(CheckFailure.class).hasMessageContaining("first failing checkout took only");
        assertThat(stub.receivedBodies.get(stub.received.lastIndexOf("PUT /api/payments/admin/chaos")))
            .contains("\"errorRate\":0.0");
    }

    @Test
    void chaosFailsWhenPaymentNeverRecoversButStillSwitchesChaosOff() {
        stubChaos(false);

        assertThatThrownBy(() -> new PaymentChaosCheck(6, Duration.ofMillis(50), Duration.ofMillis(300), TICK, 50)
            .run(ctx)).isInstanceOf(CheckFailure.class).hasMessageContaining("recover");
        assertThat(stub.receivedBodies.get(stub.received.lastIndexOf("PUT /api/payments/admin/chaos")))
            .contains("\"errorRate\":0.0");
    }

    @Test
    void chaosFailsWhenCheckoutDoesNotAnswerPaymentUnavailable() {
        stubChaos(true);
        stub.on("POST /api/bookings/orders", Reply.of(201, "{\"orderId\":1,\"totalInr\":1}"));

        assertThatThrownBy(() -> new PaymentChaosCheck(6, Duration.ofMillis(50), Duration.ofSeconds(1), TICK, 50)
            .run(ctx)).isInstanceOf(CheckFailure.class).hasMessageContaining("payment-unavailable");
        assertThat(stub.receivedBodies.get(stub.received.lastIndexOf("PUT /api/payments/admin/chaos")))
            .contains("\"errorRate\":0.0");
    }

    @Test
    void chaosFailsWhenTheSeatsAreNotStillHeld() {
        stubChaos(true);
        stub.on("GET /api/bookings/events/1", Reply.of(200, "{\"seats\":[{\"id\":5,\"state\":\"AVAILABLE\"},"
            + "{\"id\":9,\"state\":\"AVAILABLE\"}]}"));
        stub.on("POST /api/bookings/holds", Reply.of(201, "{\"holdId\":\"h-2\",\"quotedTotalInr\":1200}"));

        assertThatThrownBy(() -> new PaymentChaosCheck(6, Duration.ofMillis(50), Duration.ofSeconds(1), TICK, 50)
            .run(ctx)).isInstanceOf(CheckFailure.class).hasMessageContaining("held");
    }

    // 7 ---------------------------------------------------------------------------------------------------------

    @Test
    void rapidHoldsGetA429FromTheGateway() throws Exception {
        ctx.userToken = "user-jwt";
        AtomicInteger n = new AtomicInteger();
        stub.onBody("POST /api/bookings/holds", b -> n.incrementAndGet() > 10
            ? Reply.of(429, problem("rate-limited")) : Reply.of(404, problem("seat-not-found")));

        assertThat(new RateLimitCheck().run(ctx)).contains("429");
    }

    @Test
    void rapidHoldsFailWhenNothingIsEverLimited() {
        ctx.userToken = "user-jwt";
        stub.on("POST /api/bookings/holds", Reply.of(404, problem("seat-not-found")));

        assertThatThrownBy(() -> new RateLimitCheck().run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("429");
    }

    // 8 ---------------------------------------------------------------------------------------------------------

    @Test
    void forgedRolesHeaderWithAUserTokenIsRefused() throws Exception {
        ctx.userToken = "user-jwt";
        stub.on("GET /api/bookings/admin/events/1/integrity", Reply.of(403, problem("forbidden")));

        assertThat(new ForgedRolesCheck().run(ctx)).contains("403");
        assertThat(stub.receivedAuth.get(0)).isEqualTo("Bearer user-jwt");
        assertThat(stub.receivedRoles.get(0)).isEqualTo("ADMIN");
    }

    @Test
    void forgedRolesHeaderThatGetsThroughFails() {
        ctx.userToken = "user-jwt";
        stub.on("GET /api/bookings/admin/events/1/integrity", Reply.of(200, "{}"));

        assertThatThrownBy(() -> new ForgedRolesCheck().run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("200");
    }

    // 9 ---------------------------------------------------------------------------------------------------------

    private void stubSecurity() {
        ctx.userToken = "user-jwt";
        ctx.email = "smoke-1@example.com";
        ctx.password = "pw-pw-pw-pw";
        stub.on("POST /api/bookings/admin/events/1/reset", Reply.of(401, problem("missing-user")));
        stub.on("GET /api/bookings/orders/1", Reply.of(401, problem("missing-user")));
        stub.on("GET /", Reply.of(200, "<html>", java.util.Map.of("X-Frame-Options", "DENY",
            "X-Content-Type-Options", "nosniff", "Referrer-Policy", "no-referrer",
            "Permissions-Policy", "camera=()", "Cross-Origin-Opener-Policy", "same-origin")));
        stub.on("GET /api/catalog/events/1", Reply.of(200, "{}", java.util.Map.of("Cache-Control", "no-store")));
        stub.on("POST /api/auth/logout", Reply.of(204, ""));
        stub.on("GET /api/auth/me", Reply.of(401, problem("missing-user")));
    }

    @Test
    void securityControlsAllHold() throws Exception {
        stubSecurity();

        String detail = new SecurityCheck().run(ctx);

        assertThat(detail).contains("logout");
        int forged = stub.receivedUserIdHeader.indexOf(true);
        assertThat(forged).isGreaterThanOrEqualTo(0);
        assertThat(stub.receivedAuth.get(forged)).isEmpty();
    }

    @Test
    void securityFailsAndListsEveryBrokenControl() {
        stubSecurity();
        stub.on("POST /api/bookings/admin/events/1/reset", Reply.of(204, ""));
        stub.on("GET /api/catalog/events/1", Reply.of(200, "{}"));
        stub.on("GET /api/auth/me", Reply.of(200, "{}"));

        assertThatThrownBy(() -> new SecurityCheck().run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("anonymous reset")
            .hasMessageContaining("no-store")
            .hasMessageContaining("logout");
    }

    @Test
    void securityFailsWhenAForgedUserIdIsAccepted() {
        stubSecurity();
        stub.on("GET /api/bookings/orders/1", Reply.of(200, "{}"));

        assertThatThrownBy(() -> new SecurityCheck().run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("X-User-Id");
    }

    // 10 --------------------------------------------------------------------------------------------------------

    @Test
    void cleanupSwitchesChaosOffAndResetsTheEvent() throws Exception {
        stub.on("PUT /api/payments/admin/chaos", Reply.of(204, ""));
        stub.on("POST /api/bookings/admin/events/1/reset", Reply.of(204, ""));

        assertThat(new CleanupCheck().run(ctx)).contains("reset");
        assertThat(stub.received).contains("PUT /api/payments/admin/chaos", "POST /api/bookings/admin/events/1/reset");
    }

    @Test
    void cleanupStillResetsTheEventWhenChaosCannotBeSwitchedOffAndFails() {
        stub.on("PUT /api/payments/admin/chaos", Reply.of(500, ""));
        stub.on("POST /api/bookings/admin/events/1/reset", Reply.of(204, ""));

        assertThatThrownBy(() -> new CleanupCheck().run(ctx)).isInstanceOf(CheckFailure.class)
            .hasMessageContaining("chaos");
        assertThat(stub.received).contains("POST /api/bookings/admin/events/1/reset");
    }

    @Test
    void thereAreTenChecksInThePlannedOrder() {
        assertThat(dev.surge.smoke.Checks.all()).extracting(dev.surge.smoke.Check::name).hasSize(10);
    }
}
