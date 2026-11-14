package dev.surge.catalog.search;

import dev.surge.catalog.event.EventView;
import java.util.List;

/** One page of search hits; {@code total} counts every match, not just this page. */
public record SearchPage(List<EventView> items, long total, int page, int size) {
}
