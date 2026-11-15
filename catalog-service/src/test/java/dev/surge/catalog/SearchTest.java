package dev.surge.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.catalog.event.EventView;
import dev.surge.catalog.search.SearchPage;
import dev.surge.catalog.support.Identity;
import dev.surge.catalog.support.IntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Search over the seeded events (ids 1-30). Other tests import more events and the index is rebuilt after each
 * import, so these tests assert "every hit satisfies the filter" and "the known events are in there", never exact
 * totals.
 */
@IntegrationTest
class SearchTest {

    private static final String SEARCH = "/api/catalog/events/search";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    private static MockHttpServletRequestBuilder request(String... paramsAndValues) {
        MockHttpServletRequestBuilder request = get(SEARCH).with(Identity.anonymous());
        for (int i = 0; i < paramsAndValues.length; i += 2) {
            request.param(paramsAndValues[i], paramsAndValues[i + 1]);
        }
        return request;
    }

    private SearchPage search(String... paramsAndValues) throws Exception {
        String body = mvc.perform(request(paramsAndValues)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return json.readValue(body, SearchPage.class);
    }

    private static List<Long> ids(SearchPage page) {
        return page.items().stream().map(EventView::id).toList();
    }

    @Test
    void typoStillFindsEvent() throws Exception {
        SearchPage page = search("q", "coldply");

        assertThat(ids(page)).contains(5L);
        EventView hit = page.items().stream().filter(e -> e.id() == 5).findFirst().orElseThrow();
        assertThat(hit.title()).isEqualTo("Coldplay Tribute Live");
        assertThat(hit.venueName()).isEqualTo("Coca-Cola Arena, Dubai");
        assertThat(hit.city()).isEqualTo("Dubai");
        assertThat(hit.zoneId()).isEqualTo("Asia/Dubai");
        assertThat(hit.totalSeats()).isEqualTo(51);
        assertThat(hit.minPriceInr()).isEqualByComparingTo("1499");
        assertThat(hit.maxPriceInr()).isEqualByComparingTo("4999");
        assertThat(hit.startsAt()).isEqualTo(Instant.parse("2026-12-09T15:00:00Z"));
        assertThat(hit.saleOpensAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    void mixedCaseAndPaddingAndWholeWordsAlsoMatch() throws Exception {
        assertThat(ids(search("q", "COLDPLAY"))).contains(5L);
        assertThat(ids(search("q", "  cOlDpLaY   tribute  "))).contains(5L);
        // all terms must match (operator and): a second, unrelated word leaves nothing
        assertThat(search("q", "coldplay hamlet").items()).isEmpty();
        // the artist and the venue are searched too, with a typo
        assertThat(ids(search("q", "yelow skies"))).contains(5L);
        assertThat(ids(search("q", "Coca Cola Arena"))).contains(2L, 5L);
    }

    @Test
    void filtersOnlyQueryWorks() throws Exception {
        SearchPage page = search("city", "Delhi", "category", "concert", "size", "100");

        assertThat(page.total()).isGreaterThanOrEqualTo(3);
        assertThat(page.items()).isNotEmpty().allSatisfy(e -> {
            assertThat(e.city()).isEqualTo("Delhi");
            assertThat(e.category()).isEqualTo("concert");
        });
        assertThat(ids(page)).contains(1L, 4L);
        // no q: ordered by start time
        assertThat(page.items().stream().map(EventView::startsAt).toList()).isSorted();
        // people type the city and the category in any case
        SearchPage shouted = search("city", "  dELHI ", "category", "CONCERT", "size", "100");
        assertThat(shouted.total()).isEqualTo(page.total());
        assertThat(ids(shouted)).isEqualTo(ids(page));
    }

    @Test
    void noParametersAtAllListsEventsByStartTime() throws Exception {
        SearchPage page = search();

        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.items()).hasSize(20);
        assertThat(page.total()).isGreaterThanOrEqualTo(30);
        assertThat(page.items().stream().map(EventView::startsAt).toList()).isSorted();
    }

    @Test
    void priceAndDateFilters() throws Exception {
        SearchPage cheap = search("maxPrice", "1499", "size", "100");
        assertThat(cheap.items()).isNotEmpty()
                .allSatisfy(e -> assertThat(e.minPriceInr()).isLessThanOrEqualTo(new BigDecimal("1499")));
        assertThat(search("maxPrice", "1498.99").items()).isEmpty();
        assertThat(search("maxPrice", "0").total()).isZero();

        SearchPage window = search("from", "2026-12-05T00:00:00Z", "to", "2026-12-07T23:59:59Z", "size", "100");
        assertThat(ids(window)).contains(1L, 2L, 3L, 4L);
        assertThat(window.items()).allSatisfy(e -> assertThat(e.startsAt())
                .isBetween(Instant.parse("2026-12-05T00:00:00Z"), Instant.parse("2026-12-07T23:59:59Z")));
        assertThat(search("from", "2999-01-01T00:00:00Z").items()).isEmpty();

        SearchPage combined = search("q", "jazz", "from", "2026-12-01T00:00:00Z", "maxPrice", "2000", "city",
                "singapore");
        assertThat(ids(combined)).contains(3L);
    }

    @Test
    void geoRadiusFilter() throws Exception {
        SearchPage dubai = search("lat", "25.2048", "lon", "55.2708", "radiusKm", "50", "size", "100");

        assertThat(dubai.items()).isNotEmpty().allSatisfy(e -> assertThat(e.city()).isEqualTo("Dubai"));
        assertThat(ids(dubai)).contains(2L, 5L);

        SearchPage singapore = search("lat", "1.3", "lon", "103.85", "radiusKm", "50", "size", "100");
        assertThat(singapore.items()).isNotEmpty().allSatisfy(e -> assertThat(e.city()).isEqualTo("Singapore"));

        // 20 km around the middle of the Arabian Sea reaches no venue; 5000 km around Dubai reaches Delhi too
        assertThat(search("lat", "15", "lon", "65", "radiusKm", "20").items()).isEmpty();
        SearchPage wide = search("lat", "25.2048", "lon", "55.2708", "radiusKm", "5000", "size", "100");
        assertThat(wide.items()).extracting(EventView::city).contains("Dubai", "Delhi");

        // geo combines with the text query and the other filters
        assertThat(ids(search("q", "coldplay", "lat", "25.2048", "lon", "55.2708", "radiusKm", "50"))).contains(5L);
        assertThat(search("q", "coldplay", "lat", "28.5677", "lon", "77.2433", "radiusKm", "50").items()).isEmpty();
    }

    @Test
    void unknownCityReturnsEmptyPage() throws Exception {
        SearchPage page = search("city", "Atlantis");

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(20);
        assertThat(search("q", "coldplay", "city", "Atlantis").total()).isZero();
        assertThat(search("category", "opera").total()).isZero();
        assertThat(search("q", "zzzzqqqqxxxx").items()).isEmpty();
    }

    @Test
    void pageBeyondEndIsEmpty() throws Exception {
        SearchPage beyond = search("page", "1000", "size", "20");
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.total()).isGreaterThanOrEqualTo(30);
        assertThat(beyond.page()).isEqualTo(1000);

        // far past Elasticsearch's 10,000-result window, and a page number that would overflow an int offset
        for (String page : new String[] {"100", "100000", "2147483647"}) {
            SearchPage deep = search("page", page, "size", "100");
            assertThat(deep.items()).isEmpty();
            assertThat(deep.total()).isGreaterThanOrEqualTo(30);
        }
        // pages do not overlap
        SearchPage first = search("page", "0", "size", "10");
        SearchPage second = search("page", "1", "size", "10");
        assertThat(first.items()).hasSize(10);
        assertThat(second.items()).hasSize(10);
        assertThat(ids(first)).doesNotContainAnyElementsOf(ids(second));
        assertThat(second.page()).isEqualTo(1);
        assertThat(second.size()).isEqualTo(10);
    }

    @Test
    void invalidParamsAre400() throws Exception {
        List<String[]> bad = List.of(
                new String[] {"size", "101"}, new String[] {"size", "0"}, new String[] {"size", "abc"},
                new String[] {"page", "-1"}, new String[] {"page", "x"},
                new String[] {"radiusKm", "10"},
                new String[] {"lat", "10", "lon", "10"},
                new String[] {"lat", "10", "radiusKm", "10"},
                new String[] {"lon", "10", "radiusKm", "10"},
                new String[] {"lat", "91", "lon", "10", "radiusKm", "10"},
                new String[] {"lat", "-91", "lon", "10", "radiusKm", "10"},
                new String[] {"lat", "10", "lon", "181", "radiusKm", "10"},
                new String[] {"lat", "NaN", "lon", "10", "radiusKm", "10"},
                new String[] {"lat", "10", "lon", "10", "radiusKm", "0"},
                new String[] {"lat", "10", "lon", "10", "radiusKm", "20001"},
                new String[] {"lat", "10", "lon", "10", "radiusKm", "NaN"},
                new String[] {"lat", "10", "lon", "10", "radiusKm", "-5"},
                new String[] {"from", "2027-01-02T00:00:00Z", "to", "2027-01-01T00:00:00Z"},
                new String[] {"from", "tomorrow"},
                new String[] {"maxPrice", "-1"}, new String[] {"maxPrice", "cheap"},
                new String[] {"q", "x".repeat(201)});
        for (String[] params : bad) {
            mvc.perform(request(params))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://surge.dev/problems/validation-failed"))
                    .andExpect(jsonPath("$.requestId").exists());
        }
        // the boundaries themselves are fine
        search("size", "100");
        search("size", "1");
        search("lat", "90", "lon", "-180", "radiusKm", "1");
        search("lat", "-90", "lon", "180", "radiusKm", "20000");
    }

    @Test
    void awkwardTextNeverCausesAServerError() throws Exception {
        for (String q : new String[] {"", "   ", "\"", "(", "\\", "*", "?", "+-=&&||!(){}[]^\"~*?:\\/", "AND", "OR NOT",
                "title:coldplay", "a", "é", "日本語", "'; DROP TABLE event; --",
                "<script>alert(1)</script>", "x".repeat(200)}) {
            search("q", q);
        }
        // blank means "no query": the same as no q at all
        assertThat(search("q", "   ").total()).isEqualTo(search().total());
        search("city", "");
        search("category", "  ");
    }

    @Test
    void searchResultsCarryTheSameFieldsAsTheListEndpoint() throws Exception {
        mvc.perform(request("q", "desert beats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(2))
                .andExpect(jsonPath("$.items[0].title").value("Desert Beats"))
                .andExpect(jsonPath("$.items[0].artist").value("Desert Beats Crew"))
                .andExpect(jsonPath("$.items[0].category").value("concert"))
                .andExpect(jsonPath("$.items[0].totalSeats").value(100))
                .andExpect(jsonPath("$.items[0].description").isNotEmpty())
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }
}
