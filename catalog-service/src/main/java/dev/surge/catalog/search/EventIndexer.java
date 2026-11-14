package dev.surge.catalog.search;

import dev.surge.catalog.event.Event;
import dev.surge.catalog.event.EventRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.stereotype.Component;

/**
 * Keeps the {@code events} index in step with MySQL. At startup it creates the index if it is missing (settings and
 * mapping from {@code elasticsearch/events-mapping.json}) and indexes every event; imports call {@link #reindexAll()}
 * again. Documents use the event id as their id, so re-indexing overwrites and is safe to repeat at any time.
 * <p>
 * Refresh strategy: a full reindex ends with an explicit refresh (rare, and the caller expects to search at once);
 * the single-field {@code seatsLeft} updates from the orders consumer do not refresh and become searchable with the
 * index's normal 1 s refresh, so a busy sale is not slowed down by one refresh per order.
 */
@Component
public class EventIndexer implements ApplicationRunner {

    public static final String INDEX = "events";
    static final String MAPPING_RESOURCE = "elasticsearch/events-mapping.json";
    private static final int BATCH = 500;
    private static final Logger log = LoggerFactory.getLogger(EventIndexer.class);

    private final ElasticsearchOperations operations;
    private final EventRepository events;

    public EventIndexer(ElasticsearchOperations operations, EventRepository events) {
        this.operations = operations;
        this.events = events;
    }

    /** A failure here (Elasticsearch still starting) must not stop catalog: the next import or restart reindexes. */
    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureIndex();
            log.info("Indexed {} event(s) into '{}'", reindexAll(), INDEX);
        } catch (RuntimeException e) {
            log.error("Could not build the '{}' search index at startup; search stays unavailable until the next "
                    + "import or restart", INDEX, e);
        }
    }

    /** Creates the index with its mapping unless it already exists. */
    @SuppressWarnings("unchecked")
    public void ensureIndex() {
        IndexOperations index = operations.indexOps(IndexCoordinates.of(INDEX));
        if (index.exists()) {
            return;
        }
        Document definition = Document.parse(readMapping());
        Map<String, Object> settings = (Map<String, Object>) definition.get("settings");
        Map<String, Object> mappings = (Map<String, Object>) definition.get("mappings");
        index.create(settings, Document.from(mappings));
        log.info("Created Elasticsearch index '{}'", INDEX);
    }

    /** Indexes every event in MySQL (overwriting what is there) and refreshes. @return how many events */
    public int reindexAll() {
        ensureIndex();
        IndexCoordinates coordinates = IndexCoordinates.of(INDEX);
        int total = 0;
        Page<Event> page;
        int number = 0;
        do {
            page = events.findAllBy(PageRequest.of(number++, BATCH, Sort.by("id")));
            List<IndexQuery> batch = new ArrayList<>();
            for (Event event : page.getContent()) {
                batch.add(new IndexQueryBuilder().withId(String.valueOf(event.getId()))
                        .withObject(EventDocument.of(event)).build());
            }
            if (!batch.isEmpty()) {
                operations.bulkIndex(batch, coordinates);
                total += batch.size();
            }
        } while (page.hasNext());
        operations.indexOps(coordinates).refresh();
        return total;
    }

    /**
     * Sets {@code seatsLeft} on one document. Never throws: MySQL is the source of truth and already holds the new
     * value, so a failure here is only logged and the next reindex repairs the document.
     */
    public void updateSeatsLeft(long eventId, int seatsLeft) {
        try {
            UpdateQuery update = UpdateQuery.builder(String.valueOf(eventId))
                    .withDocument(Document.from(Map.of("seatsLeft", seatsLeft))).build();
            operations.update(update, IndexCoordinates.of(INDEX));
        } catch (RuntimeException e) {
            log.warn("Could not update seatsLeft of event {} in the search index (MySQL is already correct; the next "
                    + "reindex fixes it): {}", eventId, e.toString());
        }
    }

    private static String readMapping() {
        try (InputStream in = new ClassPathResource(MAPPING_RESOURCE).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + MAPPING_RESOURCE, e);
        }
    }
}
