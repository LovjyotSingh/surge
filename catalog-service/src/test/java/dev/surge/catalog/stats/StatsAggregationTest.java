package dev.surge.catalog.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatsAggregationTest {

    @Test
    void rowsOfTheSameDateAreSummedAndDatesAreSorted() {
        List<StatsController.SalesRow> rows = List.of(
                new StatsController.SalesRow(LocalDate.parse("2026-12-03"), 1, new BigDecimal("10.00")),
                new StatsController.SalesRow(LocalDate.parse("2026-12-01"), 2, new BigDecimal("20.50")),
                new StatsController.SalesRow(LocalDate.parse("2026-12-01"), 3, new BigDecimal("30.25")));

        List<DailySales> result = StatsController.aggregate(rows);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).date()).isEqualTo(LocalDate.parse("2026-12-01"));
        assertThat(result.get(0).ticketsSold()).isEqualTo(5);
        assertThat(result.get(0).revenueInr()).isEqualByComparingTo("50.75");
        assertThat(result.get(1).date()).isEqualTo(LocalDate.parse("2026-12-03"));
        assertThat(StatsController.aggregate(List.of())).isEmpty();
    }
}
