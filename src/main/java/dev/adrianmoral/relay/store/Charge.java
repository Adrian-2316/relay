package dev.adrianmoral.relay.store;

import java.time.Instant;

public record Charge(String id, String customerEmail, long amountCents, Instant createdAt,
                     String invoiceId, boolean refunded) {

    public Charge asRefunded() {
        return new Charge(id, customerEmail, amountCents, createdAt, invoiceId, true);
    }
}
