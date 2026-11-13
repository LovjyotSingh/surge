package dev.surge.booking.error;

import java.time.Instant;

public class SaleNotOpenException extends RuntimeException {
    private final Instant saleOpensAt;

    public SaleNotOpenException(Instant saleOpensAt) {
        super("Sale opens at " + saleOpensAt);
        this.saleOpensAt = saleOpensAt;
    }

    public Instant saleOpensAt() {
        return saleOpensAt;
    }
}
