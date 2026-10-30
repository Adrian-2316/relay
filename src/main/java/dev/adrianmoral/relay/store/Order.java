package dev.adrianmoral.relay.store;

import java.time.Instant;

public record Order(String id, String customerEmail, String item, long totalCents, String status,
                    Instant deliveredAt, String address, String carrier, String trackingId,
                    Instant carrierPickupAt, String chargeId) {

    public Order withAddress(String newAddress) {
        return new Order(id, customerEmail, item, totalCents, status, deliveredAt, newAddress,
                carrier, trackingId, carrierPickupAt, chargeId);
    }
}
