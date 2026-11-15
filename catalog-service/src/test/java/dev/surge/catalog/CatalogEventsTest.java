package dev.surge.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.catalog.support.Identity;
import dev.surge.catalog.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class CatalogEventsTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void seedHasThirtyEventsAndThreeVenues() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE id <= 30", Integer.class)).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM venue", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE id <= 30 AND starts_at >= '2026-12-05' "
                + "AND starts_at < '2027-04-01'", Integer.class)).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT category) FROM event WHERE id <= 30", Integer.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE title = 'Coldplay Tribute Live'",
                Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void firstThreeEventsEqualBookingsPhaseOneSeed() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT e.id, e.title, v.name AS venue_name, v.zone_id,
                       DATE_FORMAT(e.sale_opens_at, '%Y-%m-%d %H:%i:%s') AS sale, e.total_seats, e.seats_left
                FROM event e JOIN venue v ON v.id = e.venue_id WHERE e.id <= 3 ORDER BY e.id""");
        assertThat(rows).extracting(r -> r.get("title")).containsExactly(
                "Neon Nights Live — Flash Sale", "Desert Beats", "Marina Jazz Night");
        assertThat(rows).extracting(r -> r.get("venue_name")).containsExactly(
                "Jawaharlal Nehru Stadium, Delhi", "Coca-Cola Arena, Dubai", "Esplanade Concert Hall, Singapore");
        assertThat(rows).extracting(r -> r.get("zone_id")).containsExactly(
                "Asia/Kolkata", "Asia/Dubai", "Asia/Singapore");
        assertThat(rows).extracting(r -> r.get("sale")).containsOnly("2026-01-01 00:00:00");
        assertThat(rows).extracting(r -> ((Number) r.get("total_seats")).intValue()).containsExactly(500, 100, 100);
        assertThat(rows).extracting(r -> ((Number) r.get("seats_left")).intValue()).containsExactly(500, 100, 100);
    }

    @Test
    void listReturnsEventsSortedByStartWithDefaultPageSize() throws Exception {
        mvc.perform(get("/api/catalog/events").with(Identity.anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.content.length()").value(20))
                .andExpect(jsonPath("$.totalElements").value(Matchers.greaterThanOrEqualTo(30)))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Neon Nights Live — Flash Sale"))
                .andExpect(jsonPath("$.content[0].venueName").value("Jawaharlal Nehru Stadium, Delhi"))
                .andExpect(jsonPath("$.content[0].city").value("Delhi"))
                .andExpect(jsonPath("$.content[0].zoneId").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.content[0].minPriceInr").value(1499))
                .andExpect(jsonPath("$.content[0].maxPriceInr").value(4999))
                .andExpect(jsonPath("$.content[0].totalSeats").value(500))
                .andExpect(jsonPath("$.content[0].seatsLeft").value(500))
                .andExpect(jsonPath("$.content[0].category").value("concert"));
        mvc.perform(get("/api/catalog/events").with(Identity.anonymous()).param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(10))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    void detailReturnsOneEvent() throws Exception {
        mvc.perform(get("/api/catalog/events/2").with(Identity.anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.title").value("Desert Beats"))
                .andExpect(jsonPath("$.venueName").value("Coca-Cola Arena, Dubai"))
                .andExpect(jsonPath("$.totalSeats").value(100))
                .andExpect(jsonPath("$.description").isNotEmpty());
    }

    @Test
    void unknownEventIsNotFoundProblem() throws Exception {
        mvc.perform(get("/api/catalog/events/999999").with(Identity.anonymous()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/event-not-found"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void pageSizeAboveOneHundredIsRejected() throws Exception {
        mvc.perform(get("/api/catalog/events").with(Identity.anonymous()).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/validation-failed"));
        mvc.perform(get("/api/catalog/events").with(Identity.anonymous()).param("size", "100"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/catalog/events").with(Identity.anonymous()).param("size", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/events").with(Identity.anonymous()).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/events").with(Identity.anonymous()).param("size", "abc"))
                .andExpect(status().isBadRequest());
    }

    /** The gateway matches /api/catalog/admin/** case-sensitively, so catalog must not match it case-insensitively. */
    @Test
    void adminPathIsCaseSensitive() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "events.csv", "text/csv",
                "title,artist,category,venueId,startsAt,saleOpensAt,rows,description\n".getBytes(StandardCharsets.UTF_8));
        for (String path : List.of("/api/catalog/ADMIN/events/import", "/api/catalog/Admin/events/import",
                "/api/catalog/admin/EVENTS/import")) {
            mvc.perform(multipart(path).file(file).with(Identity.admin()))
                    .andExpect(status().isNotFound());
        }
    }
}
