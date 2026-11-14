package dev.surge.catalog.search;

import dev.surge.catalog.error.InvalidParameterException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

/**
 * A validated, normalised search request. Text is trimmed (a blank {@code q}, {@code city} or {@code category}
 * means "not given"), {@code city} and {@code category} are lower-cased the way the index stores them, and every
 * rule of the endpoint lives here so that bad input is a 400 before Elasticsearch is asked anything.
 */
public record SearchQuery(String q, String city, String category, Instant from, Instant to, BigDecimal maxPrice,
                          Double lat, Double lon, Double radiusKm, int page, int size) {

    public static final int MAX_SIZE = 100;
    public static final int MAX_TEXT = 200;
    static final double MAX_RADIUS_KM = 20_000;
    static final BigDecimal MAX_PRICE = new BigDecimal("1000000000");

    public SearchQuery {
        q = blankToNull(q);
        city = blankToNull(city);
        category = blankToNull(category);
        if (q != null && q.length() > MAX_TEXT) {
            throw new InvalidParameterException("q must be at most " + MAX_TEXT + " characters");
        }
        if (city != null && city.length() > MAX_TEXT) {
            throw new InvalidParameterException("city must be at most " + MAX_TEXT + " characters");
        }
        if (category != null && category.length() > MAX_TEXT) {
            throw new InvalidParameterException("category must be at most " + MAX_TEXT + " characters");
        }
        city = city == null ? null : city.toLowerCase(Locale.ROOT);
        category = category == null ? null : category.toLowerCase(Locale.ROOT);
        if (page < 0) {
            throw new InvalidParameterException("page must be 0 or more");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidParameterException("size must be between 1 and " + MAX_SIZE);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidParameterException("from must not be after to");
        }
        if (maxPrice != null && (maxPrice.signum() < 0 || maxPrice.compareTo(MAX_PRICE) > 0)) {
            throw new InvalidParameterException("maxPrice must be between 0 and " + MAX_PRICE.toPlainString());
        }
        if (lat != null || lon != null || radiusKm != null) {
            if (lat == null || lon == null || radiusKm == null) {
                throw new InvalidParameterException("lat, lon and radiusKm must be given together");
            }
            // written as "not in range" so that NaN fails too
            if (!(lat >= -90 && lat <= 90)) {
                throw new InvalidParameterException("lat must be between -90 and 90");
            }
            if (!(lon >= -180 && lon <= 180)) {
                throw new InvalidParameterException("lon must be between -180 and 180");
            }
            if (!(radiusKm >= 1 && radiusKm <= MAX_RADIUS_KM)) {
                throw new InvalidParameterException("radiusKm must be between 1 and 20000");
            }
        }
    }

    boolean hasGeo() {
        return radiusKm != null;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
