package dev.surge.smoke.checks;

import static dev.surge.smoke.SmokeContext.expect;

import dev.surge.smoke.Check;
import dev.surge.smoke.CheckFailure;
import dev.surge.smoke.SmokeContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;

/** Fuzzy search ("coldply" finds Coldplay Tribute Live) and the city filter. Public reads, no sign-in. */
public final class SearchCheck implements Check {

    static final String TITLE = "Coldplay Tribute Live";

    @Override
    public String name() {
        return "search-fuzzy-and-city";
    }

    @Override
    public String run(SmokeContext ctx) {
        JsonNode hits = expect(ctx.api().get("/api/catalog/events/search?q=coldply", null), 200, "search")
            .json().path("items");
        String city = null;
        for (JsonNode hit : hits) {
            if (TITLE.equals(hit.path("title").asString(""))) {
                city = hit.path("city").asString("");
            }
        }
        if (city == null) {
            throw new CheckFailure("search for \"coldply\" did not return \"" + TITLE + "\" (" + hits.size()
                + " hit(s))");
        }
        String encoded = URLEncoder.encode(city, StandardCharsets.UTF_8);
        JsonNode inCity = expect(ctx.api().get("/api/catalog/events/search?city=" + encoded, null), 200,
            "city filter").json().path("items");
        if (inCity.isEmpty()) {
            throw new CheckFailure("city filter " + city + " returned nothing");
        }
        for (JsonNode e : inCity) {
            String other = e.path("city").asString("");
            if (!city.equalsIgnoreCase(other)) {
                throw new CheckFailure("city filter " + city + " let through an event in " + other);
            }
        }
        return "\"coldply\" found \"" + TITLE + "\"; city=" + city + " gives " + inCity.size() + " event(s)";
    }
}
