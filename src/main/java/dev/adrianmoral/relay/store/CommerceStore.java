package dev.adrianmoral.relay.store;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * In-memory stand-in for the shop's real systems (Shopify orders, Stripe charges, DHL shipments,
 * the EU VIES VAT registry). Swap each method for an HTTP client to go live.
 */
@Component
public class CommerceStore {

    private static final Pattern EU_VAT = Pattern.compile("^(AT|BE|DE|ES|FR|IT|NL|PT)[0-9A-Z]{8,12}$");

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final List<Charge> charges = new CopyOnWriteArrayList<>();
    private final List<String> refundLog = new CopyOnWriteArrayList<>();
    private final List<String> sentInvoices = new CopyOnWriteArrayList<>();
    private final Map<String, String> vatRegistry = Map.of(
            "IT03912870165", "CAFFE ROSSI SRL",
            "ESB26451234", "TOSTADOS RIOJA SL");
    private final Clock clock;

    public CommerceStore(Clock clock) {
        this.clock = clock;
        seed();
    }

    private void seed() {
        Instant now = clock.instant();
        Instant todayPickup = LocalTime.of(18, 0).atDate(now.atZone(ZoneId.of("Europe/Madrid")).toLocalDate())
                .atZone(ZoneId.of("Europe/Madrid")).toInstant();
        put(new Order("48213", "laura.gomez@example.com", "Hario V60 ceramic set", 3490, "delivered",
                now.minus(Duration.ofHours(1)), "Calle Portales 12, 26001 Logroño", "DHL", "JD0146000213",
                null, "ch_3Q8xLG21"));
        put(new Order("48377", "tom.becker@example.com", "Kalita Wave 185 + 2 filters packs", 4680, "label_created",
                null, "Torstraße 140, 10119 Berlin", "DHL", "JD0146000377", todayPickup, "ch_3Q9aTB77"));
        put(new Order("48150", "marco@cafferossi.it", "Espresso blend 5 kg", 11900, "delivered",
                now.minus(Duration.ofDays(6)), "Via Torino 8, 20123 Milano", "DHL", "JD0146000150",
                null, "ch_3Q6zMR50"));
        put(new Order("48302", "jonas.weber@example.com", "Ethiopia Guji 1 kg", 2950, "out_for_delivery",
                null, "Lindenallee 3, 50968 Köln", "DHL", "JD0146000302", null, "ch_3Q8yJW02"));
        put(new Order("48266", "sophie.laurent@example.com", "Descaling tablets x24", 1290, "delivered",
                now.minus(Duration.ofDays(20)), "12 Rue Oberkampf, 75011 Paris", "DHL", "JD0146000266",
                null, "ch_3Q7cSL66"));

        Instant charged = now.minus(Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                .plus(Duration.ofHours(6));
        charges.add(new Charge("ch_3Q7aPN81", "priya.nair@example.com", 1200, charged, "in_1029a", false));
        charges.add(new Charge("ch_3Q7aPN82", "priya.nair@example.com", 1200, charged.plusSeconds(120), null, false));
        charges.add(new Charge("ch_3Q8xLG21", "laura.gomez@example.com", 3490, now.minus(Duration.ofDays(3)), "in_48213", false));
        charges.add(new Charge("ch_3Q9aTB77", "tom.becker@example.com", 4680, now.minus(Duration.ofHours(5)), "in_48377", false));
    }

    private void put(Order order) {
        orders.put(order.id(), order);
    }

    public Optional<Order> findOrder(String id) {
        return Optional.ofNullable(orders.get(id.replace("#", "").trim()));
    }

    public List<Order> ordersFor(String email) {
        return orders.values().stream().filter(o -> o.customerEmail().equalsIgnoreCase(email))
                .sorted(Comparator.comparing(Order::id).reversed()).toList();
    }

    public List<Charge> listCharges(String email, int days) {
        Instant since = clock.instant().minus(Duration.ofDays(days));
        return charges.stream()
                .filter(c -> c.customerEmail().equalsIgnoreCase(email) && c.createdAt().isAfter(since))
                .sorted(Comparator.comparing(Charge::createdAt))
                .toList();
    }

    public Optional<Charge> findCharge(String id) {
        return charges.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /** Refunds a charge. Called only after a person approves it. */
    public synchronized String refund(String chargeId, long amountCents) {
        Charge charge = findCharge(chargeId).orElseThrow(() -> new IllegalArgumentException("Unknown charge " + chargeId));
        if (charge.refunded()) {
            throw new IllegalStateException("Charge " + chargeId + " was already refunded");
        }
        if (amountCents > charge.amountCents()) {
            throw new IllegalArgumentException("Refund is larger than the charge");
        }
        charges.set(charges.indexOf(charge), charge.asRefunded());
        String refundId = "re_" + Long.toString(clock.millis(), 36);
        refundLog.add(refundId + ":" + chargeId + ":" + amountCents);
        return refundId;
    }

    /** Reroutes a shipment. Called only after a person approves it. */
    public synchronized Order updateAddress(String orderId, String address) {
        Order order = findOrder(orderId).orElseThrow(() -> new IllegalArgumentException("Unknown order " + orderId));
        if (order.carrierPickupAt() == null || clock.instant().isAfter(order.carrierPickupAt())) {
            throw new IllegalStateException("Order " + orderId + " has already left the warehouse");
        }
        Order updated = order.withAddress(address);
        orders.put(orderId, updated);
        return updated;
    }

    public Map<String, Object> validateAddress(String address) {
        boolean looksValid = address.matches(".*\\d{4,5}.*") && address.contains(",");
        return Map.of("deliverable", looksValid, "normalized", address.trim());
    }

    public Map<String, Object> validateVat(String vat) {
        String normalized = vat.replaceAll("\\s", "").toUpperCase();
        if (!EU_VAT.matcher(normalized).matches()) {
            return Map.of("valid", false, "reason", "Not an EU VAT number format");
        }
        String name = vatRegistry.get(normalized);
        return name == null ? Map.of("valid", false, "reason", "Not found in VIES")
                : Map.of("valid", true, "name", name, "vat", normalized);
    }

    public Map<String, Object> track(String orderId) {
        return findOrder(orderId).<Map<String, Object>>map(o -> Map.of(
                "order", o.id(), "carrier", o.carrier(), "tracking", o.trackingId(), "status", o.status(),
                "eta", "out_for_delivery".equals(o.status()) ? "today before 14:00" : "n/a"))
                .orElse(Map.of("error", "order not found"));
    }

    /** Issues a corrected invoice. Called only after a person approves it. */
    public String sendInvoice(String orderId, String companyName, String vat) {
        String invoiceId = "INV-2026-" + orderId + "-B";
        sentInvoices.add(invoiceId + ":" + companyName + ":" + vat);
        return invoiceId;
    }

    public List<String> refundLog() {
        return new ArrayList<>(refundLog);
    }
}
