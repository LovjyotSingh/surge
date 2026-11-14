package dev.surge.catalog.search;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.transport.TransportException;
import dev.surge.catalog.event.EventView;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.NoSuchIndexException;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

/** Builds and runs the event search. Results come from the index source, so there is no database lookup per hit. */
@Service
public class EventSearchService {

    /** Elasticsearch's default max_result_window: from + size may not pass it. */
    static final long RESULT_WINDOW = 10_000;
    private static final Logger log = LoggerFactory.getLogger(EventSearchService.class);

    private final ElasticsearchOperations operations;

    public EventSearchService(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    public SearchPage search(SearchQuery q) {
        try {
            long offset = (long) q.page() * q.size();
            if (offset + q.size() > RESULT_WINDOW) {
                // Nobody can page this deep; answer with an empty page and the real total instead of an ES error.
                long total = operations.count(NativeQuery.builder().withQuery(build(q)).build(), EventDocument.class);
                return new SearchPage(List.of(), total, q.page(), q.size());
            }
            NativeQueryBuilder builder = NativeQuery.builder()
                    .withQuery(build(q))
                    .withPageable(PageRequest.of(q.page(), q.size()))
                    .withTrackTotalHits(true);
            if (q.q() != null) {
                builder.withSort(s -> s.score(sc -> sc.order(SortOrder.Desc)));
            }
            NativeQuery query = builder
                    .withSort(s -> s.field(f -> f.field("startsAt").order(SortOrder.Asc)))
                    .withSort(s -> s.field(f -> f.field("id").order(SortOrder.Asc)))
                    .build();
            SearchHits<EventDocument> hits = operations.search(query, EventDocument.class);
            List<EventView> items = hits.getSearchHits().stream()
                    .map(SearchHit::getContent).map(EventDocument::toView).toList();
            return new SearchPage(items, hits.getTotalHits(), q.page(), q.size());
        } catch (RuntimeException e) {
            if (isUnavailable(e)) {
                log.warn("Search is unavailable: {}", e.toString());
                throw new SearchUnavailableException("Search is temporarily unavailable", e);
            }
            throw e;
        }
    }

    static Query build(SearchQuery q) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        if (q.q() != null) {
            bool.must(m -> m.multiMatch(mm -> mm.query(q.q())
                    .fields("title^3", "artist^2", "venueName", "description")
                    .fuzziness("AUTO").operator(Operator.And)));
        }
        if (q.city() != null) {
            bool.filter(f -> f.term(t -> t.field("city").value(q.city())));
        }
        if (q.category() != null) {
            bool.filter(f -> f.term(t -> t.field("category").value(q.category())));
        }
        if (q.from() != null || q.to() != null) {
            bool.filter(f -> f.range(r -> r.date(d -> {
                d.field("startsAt");
                if (q.from() != null) {
                    d.gte(q.from().toString());
                }
                if (q.to() != null) {
                    d.lte(q.to().toString());
                }
                return d;
            })));
        }
        if (q.maxPrice() != null) {
            bool.filter(f -> f.range(r -> r.number(n -> n.field("minPriceInr").lte(q.maxPrice().doubleValue()))));
        }
        if (q.hasGeo()) {
            bool.filter(f -> f.geoDistance(g -> g.field("location").distance(q.radiusKm() + "km")
                    .location(l -> l.latlon(ll -> ll.lat(q.lat()).lon(q.lon())))));
        }
        return bool.build()._toQuery();
    }

    /** True for "cannot reach Elasticsearch" and "the index is not there yet", false for real bugs. */
    static boolean isUnavailable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof DataAccessResourceFailureException || t instanceof NoSuchIndexException
                    || t instanceof IOException || t instanceof TimeoutException || t instanceof TransportException) {
                return true;
            }
            if (t instanceof ElasticsearchException ee && (ee.status() == 503 || ee.status() == 429)) {
                return true;
            }
        }
        return false;
    }
}
