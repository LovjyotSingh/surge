package dev.surge.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.catalog.support.IntegrationTest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class CsvEventImporterTest {

    private static final String IMPORT = "/api/catalog/admin/events/import";
    private static final String HEADER = "title,artist,category,venueId,startsAt,saleOpensAt,rows,description\n";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private ResultActions upload(String csv, String roles) throws Exception {
        return upload(csv.getBytes(StandardCharsets.UTF_8), roles);
    }

    private ResultActions upload(byte[] csv, String roles) throws Exception {
        var request = multipart(IMPORT).file(new MockMultipartFile("file", "events.csv", "text/csv", csv));
        if (roles != null) {
            request.header("X-User-Roles", roles);
        }
        return mvc.perform(request);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void sampleFileImportsTenEvents() throws Exception {
        byte[] sample;
        try (InputStream in = new ClassPathResource("sample/events.csv").getInputStream()) {
            sample = in.readAllBytes();
        }
        int before = count("SELECT COUNT(*) FROM event");

        upload(sample, "USER,ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(10))
                .andExpect(jsonPath("$.errors.length()").value(0));

        assertThat(count("SELECT COUNT(*) FROM event")).isEqualTo(before + 10);
        // The import publishes right away: every event has its flag set and an outbox row.
        assertThat(count("SELECT COUNT(*) FROM event WHERE scheduled_published = FALSE")).isZero();
        assertThat(count("SELECT COUNT(*) FROM event e WHERE e.id > 30 AND NOT EXISTS "
                + "(SELECT 1 FROM outbox o WHERE o.aggregate_id = CAST(e.id AS CHAR))")).isZero();
        // Totals are computed from the layout and the configured tier prices.
        assertThat(count("SELECT COUNT(*) FROM event WHERE id > 30 AND (total_seats <> seats_left OR total_seats < 1 "
                + "OR min_price_inr < 1499 OR max_price_inr > 4999 OR min_price_inr > max_price_inr)")).isZero();
    }

    @Test
    void badLinesAreReportedAndGoodLinesStillImport() throws Exception {
        String csv = HEADER
                + "Good One,Artist A,concert,1,2027-02-01T13:30:00Z,2026-11-01T00:00:00Z,A:10:PLATINUM|B:10:GOLD,Fine\n"
                + "Bad Tier,Artist B,concert,2,2027-02-02T15:00:00Z,2026-11-01T00:00:00Z,A:10:DIAMOND,Bad tier\n"
                + "Good Two,Artist C,comedy,3,2027-02-03T11:00:00Z,2026-11-01T00:00:00Z,A:12:SILVER,Fine\n"
                + ",Artist D,sports,1,2027-02-04T13:30:00Z,2026-11-01T00:00:00Z,A:10:GOLD,Missing title\n"
                + "Good Three,Artist E,theatre,2,2027-02-05T15:00:00Z,2026-11-01T00:00:00Z,A:5:GOLD|B:5:SILVER,Fine\n";

        upload(csv, "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(3))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.startsWith("line 3:")))
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("DIAMOND")))
                .andExpect(jsonPath("$.errors[1]").value(org.hamcrest.Matchers.startsWith("line 5:")))
                .andExpect(jsonPath("$.errors[1]").value(org.hamcrest.Matchers.containsString("title")));
        assertThat(count("SELECT COUNT(*) FROM event WHERE title IN ('Good One', 'Good Two', 'Good Three')"))
                .isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM event WHERE title = 'Bad Tier'")).isZero();
    }

    @Test
    void otherBadValuesNeverCauseAServerError() throws Exception {
        String csv = HEADER
                + "Too Few Fields,Artist,concert,1\n"
                + "Bad Venue,Artist,concert,99,2027-02-01T13:30:00Z,2026-11-01T00:00:00Z,A:10:GOLD,x\n"
                + "Venue NaN,Artist,concert,abc,2027-02-01T13:30:00Z,2026-11-01T00:00:00Z,A:10:GOLD,x\n"
                + "Bad Date,Artist,concert,1,tomorrow,2026-11-01T00:00:00Z,A:10:GOLD,x\n"
                + "Bad Category,Artist,opera,1,2027-02-01T13:30:00Z,2026-11-01T00:00:00Z,A:10:GOLD,x\n"
                + "No Rows,Artist,concert,1,2027-02-01T13:30:00Z,2026-11-01T00:00:00Z,,x\n"
                + "Zero Seats,Artist,concert,1,2027-02-01T13:30:00Z,2026-11-01T00:00:00Z,A:0:GOLD,x\n"
                + "Many Seats,Artist,concert,1,2027-02-01T13:30:00Z,2026-11-01T00:00:00Z,A:101:GOLD,x\n"
                + "Long Label,Artist,concert,1,2027-02-01T13:30:00Z,2026-11-01T00:00:00Z,ABCDEFGHIJK:10:GOLD,x\n"
                + "Dup Label,Artist,concert,1,2027-02-01T13:30:00Z,2026-11-01T00:00:00Z,A:10:GOLD|A:10:SILVER,x\n"
                + "Malformed Row,Artist,concert,1,2027-02-01T13:30:00Z,2026-11-01T00:00:00Z,A-10-GOLD,x\n"
                + "Sale After Start,Artist,concert,1,2027-02-01T13:30:00Z,2027-03-01T00:00:00Z,A:10:GOLD,x\n"
                + "\"Unterminated,Artist,concert,1,2027-02-01T13:30:00Z,2026-11-01T00:00:00Z,A:10:GOLD,x\n";

        upload(csv, "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.errors.length()").value(13));
    }

    @Test
    void quotedFieldsMayContainCommasQuotesAndNewlinesAndKeepLineNumbers() throws Exception {
        String csv = HEADER
                + "\"Hello, \"\"World\"\"\",Artist,concert,1,2027-02-01T13:30:00Z,2026-11-01T00:00:00Z,A:10:GOLD,"
                + "\"Line one,\nline two\"\n"
                + "Bad After Multiline,Artist,nope,1,2027-02-01T13:30:00Z,2026-11-01T00:00:00Z,A:10:GOLD,x\n";

        upload(csv, "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.errors.length()").value(1))
                // header = line 1, the multi-line record = lines 2-3, so the bad row is on line 4.
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.startsWith("line 4:")));
        assertThat(jdbc.queryForObject("SELECT description FROM event WHERE title = 'Hello, \"World\"'", String.class))
                .isEqualTo("Line one,\nline two");
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        String csv = HEADER + "Never Saved,Artist,concert,1,2027-02-01T13:30:00Z,2026-11-01T00:00:00Z,A:10:GOLD,x\n";
        for (String roles : new String[] {null, "", "USER", "SUPERADMIN", "admin", "ADMINISTRATOR", "USER;ADMIN"}) {
            upload(csv, roles)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://surge.dev/problems/forbidden"))
                    .andExpect(jsonPath("$.requestId").exists());
        }
        assertThat(count("SELECT COUNT(*) FROM event WHERE title = 'Never Saved'")).isZero();
        // The exact token is what counts: whitespace around it is trimmed.
        upload(csv.replace("Never Saved", "Saved By Trimmed Admin"), " USER , ADMIN ")
                .andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(1));
    }

    @Test
    void oversizedFileIsRejected() throws Exception {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        java.util.Arrays.fill(big, (byte) 'x');
        upload(big, "ADMIN").andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void wrongHeaderOrEmptyFileIsABadRequest() throws Exception {
        upload("name,when\nA,B\n", "ADMIN").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/invalid-import"));
        upload("", "ADMIN").andExpect(status().isBadRequest());
    }

    @Test
    void missingFilePartIsABadRequest() throws Exception {
        mvc.perform(multipart(IMPORT).header("X-User-Roles", "ADMIN")).andExpect(status().isBadRequest());
    }

}
