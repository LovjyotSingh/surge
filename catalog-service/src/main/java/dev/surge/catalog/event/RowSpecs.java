package dev.surge.catalog.event;

import dev.surge.common.messaging.RowSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parsing and validation of event layouts. {@link #validate} applies exactly the rules booking enforces when it
 * consumes {@code EventScheduled}, so a layout that passes here can never end up in booking's dead-letter topic.
 */
public final class RowSpecs {

    public static final int MAX_LABEL = 10;
    public static final int MAX_SEATS_PER_ROW = 100;
    /** Catalog's own sanity cap (booking creates one seat row per seat). */
    public static final int MAX_ROWS = 100;

    private RowSpecs() {
    }

    /** Parses {@code A:25:PLATINUM|B:25:GOLD}; fails with a message naming the offending part. */
    public static List<RowSpec> parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("rows is required (for example A:25:PLATINUM|B:25:GOLD)");
        }
        List<RowSpec> rows = new ArrayList<>();
        for (String part : text.split("\\|", -1)) {
            String[] pieces = part.trim().split(":", -1);
            if (pieces.length != 3) {
                throw new IllegalArgumentException("row '" + part.trim() + "' must look like LABEL:SEATS:TIER");
            }
            int seats;
            try {
                seats = Integer.parseInt(pieces[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("seats '" + pieces[1].trim() + "' in row '" + pieces[0].trim()
                        + "' is not a number");
            }
            rows.add(new RowSpec(pieces[0].trim(), seats, pieces[2].trim().toUpperCase(Locale.ROOT)));
        }
        validate(rows);
        return rows;
    }

    /** @throws IllegalArgumentException with a reason when booking would reject this layout */
    public static void validate(List<RowSpec> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("rows must not be empty");
        }
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("at most " + MAX_ROWS + " rows are allowed");
        }
        Set<String> labels = new HashSet<>();
        for (RowSpec row : rows) {
            if (row == null) {
                throw new IllegalArgumentException("null row");
            }
            if (row.rowLabel() == null || row.rowLabel().isBlank() || row.rowLabel().length() > MAX_LABEL) {
                throw new IllegalArgumentException("rowLabel must be 1-" + MAX_LABEL + " characters");
            }
            if (!labels.add(row.rowLabel())) {
                throw new IllegalArgumentException("duplicate rowLabel " + row.rowLabel());
            }
            if (row.seats() < 1 || row.seats() > MAX_SEATS_PER_ROW) {
                throw new IllegalArgumentException("seats in row " + row.rowLabel() + " must be 1-" + MAX_SEATS_PER_ROW);
            }
            if (row.tier() == null) {
                throw new IllegalArgumentException("tier is required in row " + row.rowLabel());
            }
            try {
                Tier.valueOf(row.tier());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown tier '" + row.tier() + "' in row " + row.rowLabel()
                        + " (use PLATINUM, GOLD or SILVER)");
            }
        }
    }

    public static int totalSeats(List<RowSpec> rows) {
        return rows.stream().mapToInt(RowSpec::seats).sum();
    }
}
