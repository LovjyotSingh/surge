package dev.surge.catalog.event;

import java.util.List;

/** One page of events, in a shape that is ours to keep stable (not Spring Data's {@code PageImpl}). */
public record EventPage(List<EventView> content, int page, int size, long totalElements, int totalPages) {
}
