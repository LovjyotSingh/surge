package dev.surge.catalog.search;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog/events/search")
public class SearchController {

    private final EventSearchService search;

    public SearchController(EventSearchService search) {
        this.search = search;
    }

    @GetMapping
    public SearchPage search(@RequestParam(required = false) String q,
                             @RequestParam(required = false) String city,
                             @RequestParam(required = false) String category,
                             @RequestParam(required = false) Instant from,
                             @RequestParam(required = false) Instant to,
                             @RequestParam(required = false) BigDecimal maxPrice,
                             @RequestParam(required = false) Double lat,
                             @RequestParam(required = false) Double lon,
                             @RequestParam(required = false) Double radiusKm,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        return search.search(new SearchQuery(q, city, category, from, to, maxPrice, lat, lon, radiusKm, page, size));
    }
}
