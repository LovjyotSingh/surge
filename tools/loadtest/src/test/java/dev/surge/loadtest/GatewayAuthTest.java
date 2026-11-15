package dev.surge.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.surge.loadtest.StubServer.Reply;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The load tester signs in as admin once, mints one JWT per buyer, and never sends X-User-Id. */
class GatewayAuthTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOGIN = "POST /api/auth/login";
    private static final String MINT = "POST /api/auth/admin/test-tokens";
    private static final String RESET = "POST /api/bookings/admin/events/1/reset";
    private static final String INTEGRITY = "GET /api/bookings/admin/events/1/integrity";
    private static final String PUT_REPORT = "PUT /api/bookings/admin/loadtest-reports/latest";
    private static final String HOLDS = "POST /api/bookings/holds";
    private static final String ORDERS = "POST /api/bookings/orders";
    private static final String PASSWORD = "pw-never-printed-9f3";
    private static final String ADMIN_TOKEN = "admin.jwt.secret";
    private static final String INTEGRITY_OK = "{\"eventId\":1,\"totalSeats\":10,\"soldSeats\":3,\"orderItems\":3,"
        + "\"distinctSeatsSold\":3,\"oversold\":0,\"consistent\":true}";

    static Reply mintReply(String body) {
        JsonNode req = JSON.readTree(body);
        String prefix = req.get("prefix").asString();
        int count = req.get("count").asInt();
        String tokens = IntStream.range(0, count)
            .mapToObj(n -> "{\"userId\":\"" + prefix + "-" + n + "\",\"token\":\"tok." + prefix + "-" + n + "\"}")
            .collect(Collectors.joining(","));
        return Reply.of(200, "{\"tokens\":[" + tokens + "]}");
    }

    private static StubServer stub() throws Exception {
        String seats = IntStream.rangeClosed(1, 10)
            .mapToObj(i -> "{\"id\":" + i + ",\"rowLabel\":\"A\",\"number\":" + i + "}")
            .collect(Collectors.joining(","));
        return new StubServer()
            .on(LOGIN, Reply.of(200, "{\"token\":\"" + ADMIN_TOKEN + "\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}"))
            .onBody(MINT, GatewayAuthTest::mintReply)
            .on(RESET, Reply.of(204, ""))
            .on("GET /api/bookings/events/1", Reply.of(200, "{\"id\":1,\"seats\":[" + seats + "]}"))
            .on(HOLDS, Reply.of(201, "{\"holdId\":\"h-1\"}"))
            .on(ORDERS, Reply.of(201, "{\"id\":1}"))
            .on(INTEGRITY, Reply.of(200, INTEGRITY_OK))
            .on(PUT_REPORT, Reply.of(204, ""));
    }

    private static LoadTestConfig config(StubServer s, Path out, String... extra) {
        List<String> a = new ArrayList<>(List.of("--users", "3", "--base-url", s.baseUrl().toString(),
            "--out", out.toString()));
        a.addAll(List.of(extra));
        return LoadTestConfig.parse(a.toArray(String[]::new), name -> null);
    }

    private static int count(StubServer s, String key) {
        return (int) s.received.stream().filter(key::equals).count();
    }

    private record Run(int exit, String stdout, String stderr) {
    }

    private static Run run(LoadTestConfig cfg) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(o, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(e, true, StandardCharsets.UTF_8)) {
            int exit = LoadTestMain.run(cfg, out, err);
            return new Run(exit, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void loginAndMintingHappenOnceAndAdminCallsUseTheAdminToken(@TempDir Path out) throws Exception {
        try (StubServer s = stub()) {
            assertThat(run(config(s, out, "--admin-password", PASSWORD)).exit()).isZero();

            assertThat(count(s, LOGIN)).isEqualTo(1);
            assertThat(count(s, MINT)).isEqualTo(1);
            JsonNode login = JSON.readTree(s.receivedBodies.get(s.received.indexOf(LOGIN)));
            assertThat(login.get("email").asString()).isEqualTo("admin@surge.local");
            assertThat(login.get("password").asString()).isEqualTo(PASSWORD);
            JsonNode mint = JSON.readTree(s.receivedBodies.get(s.received.indexOf(MINT)));
            assertThat(mint.get("prefix").asString()).matches("lt-[0-9a-f]{8}");
            assertThat(mint.get("count").asInt()).isEqualTo(3);
            for (String admin : List.of(MINT, RESET, INTEGRITY, PUT_REPORT)) {
                assertThat(s.receivedAuth.get(s.received.indexOf(admin))).isEqualTo("Bearer " + ADMIN_TOKEN);
            }
            assertThat(s.receivedAuth.get(s.received.indexOf(LOGIN))).isEmpty();
        }
    }

    @Test
    void eachBuyerSendsItsOwnBearerAndNoRequestSendsXUserId(@TempDir Path out) throws Exception {
        try (StubServer s = stub()) {
            run(config(s, out, "--admin-password", PASSWORD));

            Set<String> holdTokens = new HashSet<>();
            for (int i = 0; i < s.received.size(); i++) {
                if (s.received.get(i).equals(HOLDS) || s.received.get(i).equals(ORDERS)) {
                    assertThat(s.receivedAuth.get(i)).startsWith("Bearer tok.lt-");
                }
                if (s.received.get(i).equals(HOLDS)) {
                    holdTokens.add(s.receivedAuth.get(i));
                }
            }
            assertThat(holdTokens).hasSize(3);
            assertThat(s.receivedUserIdHeader).containsOnly(false);
        }
    }

    @Test
    void missingPasswordExitsTwoWithoutAnyRequest(@TempDir Path out) throws Exception {
        try (StubServer s = stub()) {
            Run r = run(config(s, out));

            assertThat(r.exit()).isEqualTo(2);
            assertThat(r.stderr()).contains("Set SURGE_ADMIN_PASSWORD or pass --admin-password");
            assertThat(s.received).isEmpty();
        }
    }

    @Test
    void passwordFallsBackToTheEnvironment(@TempDir Path out) throws Exception {
        try (StubServer s = stub()) {
            LoadTestConfig cfg = LoadTestConfig.parse(
                new String[] {"--users", "3", "--base-url", s.baseUrl().toString(), "--out", out.toString()},
                name -> name.equals("SURGE_ADMIN_PASSWORD") ? "env-pw" : null);
            assertThat(run(cfg).exit()).isZero();
            assertThat(s.receivedBodies.get(s.received.indexOf(LOGIN))).contains("env-pw");
        }
    }

    @Test
    void configNeverPrintsThePassword() {
        LoadTestConfig cfg = LoadTestConfig.parse(new String[] {"--admin-password", PASSWORD}, name -> null);
        assertThat(cfg.toString()).doesNotContain(PASSWORD);
    }

    @Test
    void loginRejectedExitsTwo(@TempDir Path out) throws Exception {
        try (StubServer s = stub()) {
            s.on(LOGIN, Reply.of(401, "{\"type\":\"x\"}"));
            Run r = run(config(s, out, "--admin-password", PASSWORD));

            assertThat(r.exit()).isEqualTo(2);
            assertThat(r.stderr()).contains("admin login failed");
            assertThat(count(s, MINT)).isZero();
        }
    }

    @Test
    void mintingForbiddenOrMissingExitsTwoWithTheDisabledMessage(@TempDir Path out) throws Exception {
        for (int status : new int[] {403, 404}) {
            try (StubServer s = stub()) {
                s.on(MINT, Reply.of(status, ""));
                Run r = run(config(s, out, "--admin-password", PASSWORD));

                assertThat(r.exit()).isEqualTo(2);
                assertThat(r.stderr()).contains("test tokens are disabled on this deployment "
                    + "(enable them only for load tests)");
                assertThat(count(s, HOLDS)).isZero();
            }
        }
    }

    @Test
    void noTokenOrPasswordAppearsInOutput(@TempDir Path out) throws Exception {
        try (StubServer s = stub()) {
            Run r = run(config(s, out, "--admin-password", PASSWORD));
            assertThat(r.stdout() + r.stderr()).doesNotContain(PASSWORD).doesNotContain(ADMIN_TOKEN)
                .doesNotContain("tok.lt-");
        }
        try (StubServer s = stub()) {
            s.on(LOGIN, Reply.of(401, "{\"detail\":\"" + ADMIN_TOKEN + "\"}"));
            Run r = run(config(s, out, "--admin-password", PASSWORD));
            assertThat(r.stdout() + r.stderr()).doesNotContain(PASSWORD).doesNotContain(ADMIN_TOKEN);
        }
    }

    @Test
    void adminCallRetriesOnceAfterAnExpiredToken(@TempDir Path out) throws Exception {
        try (StubServer s = stub()) {
            s.on(INTEGRITY, Reply.of(401, ""), Reply.of(200, INTEGRITY_OK));
            assertThat(run(config(s, out, "--admin-password", PASSWORD)).exit()).isZero();

            assertThat(count(s, LOGIN)).isEqualTo(2);
            assertThat(count(s, INTEGRITY)).isEqualTo(2);
        }
    }

    @Test
    void mintsInBatchesWithDistinctPrefixesWhenCountExceedsTheServerMax() throws Exception {
        try (StubServer s = stub(); BookingApi api = new BookingApi(s.baseUrl())) {
            api.login("a@b.c", PASSWORD);
            List<BookingApi.TestToken> tokens = api.mintTokens("lt-abcd1234", 5, 2);

            assertThat(tokens).hasSize(5);
            assertThat(tokens.stream().map(BookingApi.TestToken::userId).collect(Collectors.toSet())).hasSize(5);
            assertThat(tokens.stream().map(BookingApi.TestToken::token).collect(Collectors.toSet())).hasSize(5);
            List<JsonNode> reqs = new ArrayList<>();
            for (int i = 0; i < s.received.size(); i++) {
                if (s.received.get(i).equals(MINT)) {
                    reqs.add(JSON.readTree(s.receivedBodies.get(i)));
                }
            }
            assertThat(reqs.stream().map(r -> r.get("count").asInt()).toList()).containsExactly(2, 2, 1);
            assertThat(reqs.stream().map(r -> r.get("prefix").asString()).toList()).doesNotHaveDuplicates()
                .allMatch(p -> p.matches("lt-[A-Za-z0-9]{1,16}"));
            assertThat(BookingApi.MAX_TOKENS_PER_CALL).isEqualTo(20000);
        }
    }

    @Test
    void shortMintResponseIsRejected() throws Exception {
        try (StubServer s = stub(); BookingApi api = new BookingApi(s.baseUrl())) {
            s.on(MINT, Reply.of(200, "{\"tokens\":[]}"));
            api.login("a@b.c", PASSWORD);
            assertThatThrownBy(() -> api.mintTokens("lt-abcd1234", 3, 2))
                .isInstanceOf(IllegalStateException.class);
        }
    }
}
