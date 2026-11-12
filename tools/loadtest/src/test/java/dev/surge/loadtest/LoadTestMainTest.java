package dev.surge.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.loadtest.StubServer.Reply;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadTestMainTest {

    private static StubServer stub(int oversold) throws Exception {
        String seats = IntStream.rangeClosed(1, 10)
            .mapToObj(i -> "{\"id\":" + i + ",\"rowLabel\":\"A\",\"number\":" + i + "}")
            .collect(Collectors.joining(","));
        return new StubServer()
            .on("POST /api/bookings/admin/events/1/reset", Reply.of(204, ""))
            .on("GET /api/bookings/events/1", Reply.of(200, "{\"id\":1,\"seats\":[" + seats + "]}"))
            .on("POST /api/bookings/holds", Reply.of(201, "{\"holdId\":\"h-1\"}"))
            .on("POST /api/bookings/orders", Reply.of(201, "{\"id\":1}"))
            .on("GET /api/bookings/admin/events/1/integrity", Reply.of(200,
                "{\"eventId\":1,\"totalSeats\":10,\"soldSeats\":3,\"orderItems\":3,"
                    + "\"distinctSeatsSold\":3,\"oversold\":" + oversold + "}"))
            .on("PUT /api/bookings/admin/loadtest-reports/latest", Reply.of(204, ""));
    }

    private static LoadTestConfig config(StubServer s, Path out) {
        return LoadTestConfig.parse(new String[] {
            "--users", "3", "--base-url", s.baseUrl().toString(), "--out", out.toString()});
    }

    @Test
    void zeroOversoldExitsZeroAndWritesReport(@TempDir Path out) throws Exception {
        try (StubServer s = stub(0)) {
            assertThat(LoadTestMain.run(config(s, out))).isZero();

            assertThat(s.received).contains("POST /api/bookings/admin/events/1/reset",
                "PUT /api/bookings/admin/loadtest-reports/latest");
            int idx = s.received.indexOf("PUT /api/bookings/admin/loadtest-reports/latest");
            assertThat(s.receivedBodies.get(idx)).contains("\"oversold\" : 0").contains("\"requests\" : 6");
            try (Stream<Path> files = Files.list(out)) {
                assertThat(files.map(p -> p.getFileName().toString()))
                    .anyMatch(n -> n.endsWith(".json")).anyMatch(n -> n.endsWith(".md"));
            }
        }
    }

    @Test
    void oversoldExitsOne(@TempDir Path out) throws Exception {
        try (StubServer s = stub(1)) {
            assertThat(LoadTestMain.run(config(s, out))).isEqualTo(1);
        }
    }
}
