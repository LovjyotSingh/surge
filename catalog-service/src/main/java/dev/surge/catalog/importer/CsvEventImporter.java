package dev.surge.catalog.importer;

import dev.surge.catalog.error.InvalidImportException;
import dev.surge.catalog.error.UploadTooLargeException;
import dev.surge.catalog.event.Event;
import dev.surge.catalog.event.EventRepository;
import dev.surge.catalog.event.PricingProperties;
import dev.surge.catalog.event.RowSpecs;
import dev.surge.catalog.event.Tier;
import dev.surge.catalog.publish.EventScheduledPublisher;
import dev.surge.catalog.search.EventIndexer;
import dev.surge.catalog.venue.Venue;
import dev.surge.catalog.venue.VenueRepository;
import dev.surge.common.messaging.RowSpec;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports events from a CSV stream. Header: {@code title,artist,category,venueId,startsAt,saleOpensAt,rows,description}.
 * The file is read as a stream (never fully in memory as text), lines are validated one by one, the valid ones are
 * saved together, and a bad line is reported with its line number instead of failing the import.
 */
@Component
public class CsvEventImporter {

    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final int MAX_DATA_ROWS = 1000;
    static final int MAX_REPORTED_ERRORS = 100;
    static final List<String> HEADER = List.of("title", "artist", "category", "venueId", "startsAt", "saleOpensAt",
            "rows", "description");
    static final Set<String> CATEGORIES = Set.of("concert", "comedy", "sports", "theatre");
    private static final int MAX_TITLE = 200;
    private static final int MAX_DESCRIPTION = 500;

    private static final Logger log = LoggerFactory.getLogger(CsvEventImporter.class);

    private final EventRepository events;
    private final VenueRepository venues;
    private final EventScheduledPublisher publisher;
    private final EventIndexer indexer;
    private final PricingProperties pricing;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    public CsvEventImporter(EventRepository events, VenueRepository venues, EventScheduledPublisher publisher, EventIndexer indexer,
                            PricingProperties pricing, JsonMapper json,
                            PlatformTransactionManager transactionManager) {
        this.events = events;
        this.venues = venues;
        this.publisher = publisher;
        this.indexer = indexer;
        this.pricing = pricing;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public ImportResult importCsv(InputStream upload) throws IOException {
        Map<Long, Venue> venueById = venues.findAll().stream().collect(Collectors.toMap(Venue::getId, Function.identity()));
        List<String> errors = new ArrayList<>();
        List<Event> valid = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new LimitedInputStream(upload, MAX_BYTES), StandardCharsets.UTF_8))) {
            // A UTF-8 byte order mark must go before parsing, or it would sit in front of a quoted header's quote.
            reader.mark(1);
            if (reader.read() != 0xFEFF) {
                reader.reset();
            }
            CsvReader csv = new CsvReader(reader);
            readHeader(csv);
            int dataRows = 0;
            for (CsvReader.Row row = csv.next(); row != null; row = csv.next()) {
                if (row.isBlank()) {
                    continue;
                }
                if (++dataRows > MAX_DATA_ROWS) {
                    addError(errors, "line " + row.line() + ": too many rows (at most " + MAX_DATA_ROWS
                            + " per import); this line and the rest were ignored");
                    break;
                }
                try {
                    valid.add(toEvent(row, venueById));
                } catch (IllegalArgumentException e) {
                    addError(errors, "line " + row.line() + ": " + e.getMessage());
                }
            }
        }
        if (!valid.isEmpty()) {
            tx.executeWithoutResult(status -> events.saveAll(valid));
            try {
                indexer.reindexAll();
            } catch (RuntimeException e) {
                // MySQL is the source of truth; the next import or restart rebuilds the index.
                log.warn("Imported {} events but could not reindex them for search yet", valid.size(), e);
            }
            try {
                publisher.publishPending();
            } catch (RuntimeException e) {
                // The events are saved and unflagged; the next startup (or import) announces them.
                log.warn("Imported {} events but could not queue their EventScheduled messages yet", valid.size(), e);
            }
        }
        return new ImportResult(valid.size(), List.copyOf(errors));
    }

    private static void readHeader(CsvReader csv) throws IOException {
        CsvReader.Row row = csv.next();
        while (row != null && row.isBlank()) {
            row = csv.next();
        }
        if (row == null) {
            throw new InvalidImportException("The file is empty; expected a header row: " + String.join(",", HEADER));
        }
        List<String> found = new ArrayList<>(row.fields().stream().map(String::trim).toList());
        found.set(0, stripBom(found.get(0)));
        if (row.error() != null || found.size() != HEADER.size() || !matchesHeader(found)) {
            throw new InvalidImportException("The first line must be the header row: " + String.join(",", HEADER));
        }
    }

    private static boolean matchesHeader(List<String> found) {
        for (int i = 0; i < HEADER.size(); i++) {
            if (!HEADER.get(i).equalsIgnoreCase(found.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static String stripBom(String s) {
        return s.startsWith("﻿") ? s.substring(1).trim() : s;
    }

    private Event toEvent(CsvReader.Row row, Map<Long, Venue> venueById) {
        if (row.error() != null) {
            throw new IllegalArgumentException(row.error());
        }
        List<String> f = row.fields();
        if (f.size() != HEADER.size()) {
            throw new IllegalArgumentException("expected " + HEADER.size() + " fields but found " + f.size()
                    + " (a field containing a comma must be wrapped in double quotes)");
        }
        String title = f.get(0).trim();
        if (title.isEmpty()) {
            throw new IllegalArgumentException("title is required");
        }
        if (title.length() > MAX_TITLE) {
            throw new IllegalArgumentException("title is longer than " + MAX_TITLE + " characters");
        }
        String artist = f.get(1).trim();
        if (artist.length() > MAX_TITLE) {
            throw new IllegalArgumentException("artist is longer than " + MAX_TITLE + " characters");
        }
        String category = f.get(2).trim().toLowerCase(Locale.ROOT);
        if (!CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("unknown category '" + f.get(2).trim()
                    + "' (use concert, comedy, sports or theatre)");
        }
        Venue venue = venueFor(f.get(3).trim(), venueById);
        Instant startsAt = instant(f.get(4), "startsAt");
        Instant saleOpensAt = instant(f.get(5), "saleOpensAt");
        if (!startsAt.isAfter(saleOpensAt)) {
            throw new IllegalArgumentException("startsAt must be after saleOpensAt");
        }
        List<RowSpec> rows = RowSpecs.parse(f.get(6));
        String description = f.get(7).trim();
        if (description.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("description is longer than " + MAX_DESCRIPTION + " characters");
        }
        BigDecimal min = null;
        BigDecimal max = null;
        for (RowSpec spec : rows) {
            BigDecimal price = pricing.price(Tier.valueOf(spec.tier()));
            min = min == null ? price : min.min(price);
            max = max == null ? price : max.max(price);
        }
        return new Event(title, artist.isEmpty() ? null : artist, category, venue, startsAt, saleOpensAt,
                description.isEmpty() ? null : description, json.writeValueAsString(rows),
                min.setScale(2), max.setScale(2), RowSpecs.totalSeats(rows));
    }

    private static Venue venueFor(String text, Map<Long, Venue> venueById) {
        long id;
        try {
            id = Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("venueId '" + text + "' is not a number");
        }
        Venue venue = venueById.get(id);
        if (venue == null) {
            throw new IllegalArgumentException("unknown venueId " + id);
        }
        return venue;
    }

    private static Instant instant(String text, String name) {
        try {
            return Instant.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " '" + text.trim()
                    + "' is not a UTC timestamp like 2027-02-01T13:30:00Z");
        }
    }

    private static void addError(List<String> errors, String message) {
        if (errors.size() < MAX_REPORTED_ERRORS) {
            errors.add(message);
        } else if (errors.size() == MAX_REPORTED_ERRORS) {
            errors.add("more errors were not listed");
        }
    }

    /** Stops reading (with a 413) the moment the stream goes past the limit, whatever the client declared. */
    private static final class LimitedInputStream extends FilterInputStream {
        private final long max;
        private long read;

        LimitedInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = super.read(buffer, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(long n) {
            read += n;
            if (read > max) {
                throw new UploadTooLargeException(max);
            }
        }
    }

}
