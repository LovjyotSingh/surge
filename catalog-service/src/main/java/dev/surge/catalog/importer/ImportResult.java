package dev.surge.catalog.importer;

import java.util.List;

/** {@code errors} holds one entry per rejected line, such as {@code "line 3: unknown tier 'X' in row A"}. */
public record ImportResult(int imported, List<String> errors) {
}
