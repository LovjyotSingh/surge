package dev.surge.catalog.stats;

import dev.surge.catalog.error.EventNotFoundException;
import dev.surge.catalog.event.EventRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog/admin/stats")
public class StatsController {

    record SalesRow(LocalDate day, long ticketsSold, BigDecimal revenueInr) {
    }

    private final JdbcTemplate jdbc;
    private final EventRepository events;

    public StatsController(JdbcTemplate jdbc, EventRepository events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @GetMapping("/daily")
    public List<DailySales> daily(@RequestParam long eventId) {
        if (!events.existsById(eventId)) {
            throw new EventNotFoundException(eventId);
        }
        List<SalesRow> rows = jdbc.query("SELECT day, tickets_sold, revenue_inr FROM sales_daily WHERE event_id = ?",
                (rs, i) -> new SalesRow(rs.getObject("day", LocalDate.class), rs.getLong("tickets_sold"),
                        rs.getBigDecimal("revenue_inr")), eventId);
        return aggregate(rows);
    }

    /** Sums the rows per date, oldest date first. */
    static List<DailySales> aggregate(List<SalesRow> rows) {
        TreeMap<LocalDate, DailySales> byDay = rows.stream().collect(Collectors.groupingBy(SalesRow::day, TreeMap::new,
                Collectors.teeing(Collectors.summingLong(SalesRow::ticketsSold),
                        Collectors.reducing(BigDecimal.ZERO, SalesRow::revenueInr, BigDecimal::add),
                        (tickets, revenue) -> new DailySales(null, tickets, revenue))));
        return byDay.entrySet().stream()
                .map(e -> new DailySales(e.getKey(), e.getValue().ticketsSold(), e.getValue().revenueInr())).toList();
    }
}
