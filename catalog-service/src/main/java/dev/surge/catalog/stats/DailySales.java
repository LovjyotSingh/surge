package dev.surge.catalog.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailySales(LocalDate date, long ticketsSold, BigDecimal revenueInr) {
}
