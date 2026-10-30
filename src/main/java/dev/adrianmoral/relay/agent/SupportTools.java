package dev.adrianmoral.relay.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.adrianmoral.relay.store.Charge;
import dev.adrianmoral.relay.store.CommerceStore;
import dev.adrianmoral.relay.store.Order;
import dev.adrianmoral.relay.store.RefundPolicy;
import dev.adrianmoral.relay.ticket.ActionType;
import dev.adrianmoral.relay.ticket.PendingAction;
import dev.adrianmoral.relay.ticket.PendingActionRepository;
import dev.adrianmoral.relay.ticket.StepKind;
import dev.adrianmoral.relay.ticket.Ticket;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dev.adrianmoral.relay.agent.ToolSpec.integer;
import static dev.adrianmoral.relay.agent.ToolSpec.number;
import static dev.adrianmoral.relay.agent.ToolSpec.oneOf;
import static dev.adrianmoral.relay.agent.ToolSpec.string;

/**
 * The tools the support agent can use, and the approval gate.
 * Read-only tools run straight away. Guarded tools (refunds, address changes, invoices) are checked,
 * then stored as a {@link PendingAction}; they only run when a person approves the ticket.
 */
@Component
public class SupportTools {

    public static final String SUBMIT_REPLY = "submit_reply";
    public static final String ESCALATE = "escalate_to_human";

    private static final Map<String, Map<String, Object>> NO_PROPS = Map.of();

    private final List<ToolSpec> specs = List.of(
            new ToolSpec("get_order", "Look up one order by its number: item, total, delivery status, address, carrier pickup time and the Stripe charge id.",
                    props("order_id", string("Order number without #, e.g. 48213")), false),
            new ToolSpec("list_orders", "List every order for a customer email. Use when the customer does not give an order number.",
                    props("customer_email", string("Customer email address")), false),
            new ToolSpec("list_charges", "List the customer's card charges in the last N days, with invoice ids. A charge with no invoice next to an identical one is usually a duplicate.",
                    props("customer_email", string("Customer email address"), "days", integer("How many days back to look, 1 to 90")), false),
            new ToolSpec("check_policy", "Check the written refund policy for a case before proposing any refund.",
                    props("case_type", oneOf("Kind of case", "damaged", "duplicate_charge", "change_of_mind", "liability", "other"),
                            "days_since_delivery", integer("Days since delivery, 0 if not delivered"),
                            "amount_eur", number("Amount in euros the customer is asking for")), false),
            new ToolSpec("validate_address", "Check that a postal address is complete and deliverable.",
                    props("address", string("Full address on one line")), false),
            new ToolSpec("validate_vat", "Check an EU VAT number against the VIES registry and get the registered company name.",
                    props("vat_number", string("VAT number with country prefix")), false),
            new ToolSpec("track_shipment", "Get live carrier tracking for an order.",
                    props("order_id", string("Order number")), false),
            new ToolSpec("issue_refund", "Refund a Stripe charge. This does not run straight away: it is queued and a person approves it before money moves. Check the policy first.",
                    props("charge_id", string("Stripe charge id, e.g. ch_..."), "amount_eur", number("Amount to refund in euros"),
                            "reason", string("Short reason shown to the approver")), true),
            new ToolSpec("update_shipping_address", "Reroute an order that has not been picked up by the carrier. Queued for a person to approve.",
                    props("order_id", string("Order number"), "new_address", string("Validated new address")), true),
            new ToolSpec("send_invoice", "Issue a corrected invoice to a company. Queued for a person to approve.",
                    props("order_id", string("Order number"), "company_name", string("Registered company name"),
                            "vat_number", string("Validated VAT number")), true),
            new ToolSpec(ESCALATE, "Hand the ticket to a person instead of replying. Use for liability or legal claims, anything over policy limits, threats, or when you are not confident.",
                    props("reason", string("Why a person must handle it"), "summary", string("Two-sentence summary for the person picking it up"),
                            "team", oneOf("Team to route to", "claims", "billing", "general")), false),
            new ToolSpec(SUBMIT_REPLY, "Finish the ticket: record the triage and the reply to the customer. Call it exactly once, last.",
                    props("intent", string("Short snake_case intent, e.g. refund_request"),
                            "sentiment", oneOf("Customer's mood", "positive", "neutral", "frustrated", "angry"),
                            "priority", oneOf("How urgent", "low", "medium", "high", "urgent"),
                            "confidence", number("0 to 1, how sure you are the reply and actions are right"),
                            "reply", string("The reply to send, in the customer's language, signed 'Northwind Support'")), false)
    );

    private final CommerceStore store;
    private final PendingActionRepository actions;
    private final ObjectMapper json;
    private final Clock clock;

    public SupportTools(CommerceStore store, PendingActionRepository actions, ObjectMapper json, Clock clock) {
        this.store = store;
        this.actions = actions;
        this.json = json;
        this.clock = clock;
    }

    public List<ToolSpec> specs() {
        return specs;
    }

    public ToolOutcome execute(Ticket ticket, String name, JsonNode in) {
        try {
            return switch (name) {
                case "get_order" -> store.findOrder(text(in, "order_id"))
                        .map(o -> ToolOutcome.ran("Looked up the order", orderView(o)))
                        .orElseGet(() -> ToolOutcome.refused("Looked up the order", "No order " + text(in, "order_id")));
                case "list_orders" -> ToolOutcome.ran("Looked up the customer's orders",
                        Map.of("orders", store.ordersFor(text(in, "customer_email")).stream().map(this::orderView).toList()));
                case "list_charges" -> ToolOutcome.ran("Listed recent charges", Map.of("charges",
                        store.listCharges(text(in, "customer_email"), Math.min(90, Math.max(1, in.path("days").asInt(7))))
                                .stream().map(this::chargeView).toList()));
                case "check_policy" -> ToolOutcome.ran("Checked the refund policy", RefundPolicy.check(
                        text(in, "case_type"), in.path("days_since_delivery").asInt(), cents(in.path("amount_eur").asDouble())));
                case "validate_address" -> ToolOutcome.ran("Validated the address", store.validateAddress(text(in, "address")));
                case "validate_vat" -> ToolOutcome.ran("Checked the VAT number", store.validateVat(text(in, "vat_number")));
                case "track_shipment" -> ToolOutcome.ran("Looked up tracking", store.track(text(in, "order_id")));
                case "issue_refund" -> proposeRefund(ticket, in);
                case "update_shipping_address" -> proposeAddressChange(ticket, in);
                case "send_invoice" -> proposeInvoice(ticket, in);
                case ESCALATE -> new ToolOutcome(StepKind.STOP, "Handed over to a person",
                        "Routed to " + text(in, "team") + ": " + text(in, "reason"),
                        Map.of("status", "escalated"), false, true);
                case SUBMIT_REPLY -> new ToolOutcome(StepKind.DONE, "Drafted the reply",
                        "Intent " + text(in, "intent") + " · " + text(in, "sentiment") + " · confidence " + text(in, "confidence"),
                        Map.of("status", "recorded"), false, true);
                default -> ToolOutcome.refused("Unknown tool", "There is no tool called " + name);
            };
        } catch (RuntimeException e) {
            return ToolOutcome.refused("Tool failed", e.getMessage());
        }
    }

    private ToolOutcome proposeRefund(Ticket ticket, JsonNode in) {
        String chargeId = text(in, "charge_id");
        long amount = cents(in.path("amount_eur").asDouble());
        Charge charge = store.findCharge(chargeId).orElse(null);
        if (charge == null || !charge.customerEmail().equalsIgnoreCase(ticket.getCustomerEmail())) {
            return ToolOutcome.refused("Refund blocked", "Charge " + chargeId + " does not belong to this customer");
        }
        if (charge.refunded()) {
            return ToolOutcome.refused("Refund blocked", "Charge " + chargeId + " is already refunded");
        }
        if (amount <= 0 || amount > charge.amountCents()) {
            return ToolOutcome.refused("Refund blocked", "Amount must be between €0.01 and the charge total " + eur(charge.amountCents()));
        }
        if (amount > RefundPolicy.AGENT_LIMIT_CENTS) {
            return ToolOutcome.refused("Refund blocked", "Refunds over " + eur(RefundPolicy.AGENT_LIMIT_CENTS) + " must be escalated");
        }
        return queue(ticket, ActionType.REFUND, in, "Refund to original card", eur(amount),
                "Stripe charge " + chargeId + " · " + text(in, "reason"));
    }

    private ToolOutcome proposeAddressChange(Ticket ticket, JsonNode in) {
        Order order = store.findOrder(text(in, "order_id")).orElse(null);
        if (order == null || !order.customerEmail().equalsIgnoreCase(ticket.getCustomerEmail())) {
            return ToolOutcome.refused("Address change blocked", "Order does not belong to this customer");
        }
        if (order.carrierPickupAt() == null || clock.instant().isAfter(order.carrierPickupAt())) {
            return ToolOutcome.refused("Address change blocked", "The carrier already has the parcel");
        }
        return queue(ticket, ActionType.ADDRESS_CHANGE, in, "Reroute shipment to new address", order.carrier(),
                "New address " + text(in, "new_address") + " · before carrier pickup");
    }

    private ToolOutcome proposeInvoice(Ticket ticket, JsonNode in) {
        Map<String, Object> vat = store.validateVat(text(in, "vat_number"));
        if (!Boolean.TRUE.equals(vat.get("valid"))) {
            return ToolOutcome.refused("Invoice blocked", "VAT number is not valid in VIES");
        }
        return queue(ticket, ActionType.SEND_INVOICE, in, "Send corrected invoice", "PDF",
                text(in, "company_name") + " · " + vat.get("vat") + " · EU reverse charge");
    }

    private ToolOutcome queue(Ticket ticket, ActionType type, JsonNode in, String summary, String amount, String detail) {
        PendingAction action = actions.save(new PendingAction(ticket.getId(), type, write(in), summary, amount, detail));
        return new ToolOutcome(StepKind.WAIT, "Proposed: " + summary.toLowerCase(Locale.ROOT) + " (" + amount + ")",
                "Held for approval. Money moves and order changes need a person.",
                Map.of("status", "queued_for_human_approval", "action_id", action.getId(),
                        "note", "Write the reply as if this will be approved; it is only sent after approval."),
                false, false);
    }

    /** Runs an approved action against the real systems. */
    public String perform(PendingAction action) {
        JsonNode in = read(action.getPayload());
        return switch (action.getType()) {
            case REFUND -> {
                long amount = cents(in.path("amount_eur").asDouble());
                yield "Refunded " + eur(amount) + " to the card (" + store.refund(text(in, "charge_id"), amount) + ")";
            }
            case ADDRESS_CHANGE -> "Rerouted to " + store.updateAddress(text(in, "order_id"), text(in, "new_address")).address();
            case SEND_INVOICE -> "Sent " + store.sendInvoice(text(in, "order_id"), text(in, "company_name"), text(in, "vat_number"));
        };
    }

    private Map<String, Object> orderView(Order o) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("order", o.id());
        view.put("item", o.item());
        view.put("total", eur(o.totalCents()));
        view.put("status", o.status());
        if (o.deliveredAt() != null) {
            view.put("days_since_delivery", Duration.between(o.deliveredAt(), clock.instant()).toDays());
        }
        view.put("address", o.address());
        if (o.carrierPickupAt() != null) {
            view.put("carrier_pickup", o.carrierPickupAt().toString());
        }
        view.put("charge_id", o.chargeId());
        return view;
    }

    private Map<String, Object> chargeView(Charge c) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("charge_id", c.id());
        view.put("amount", eur(c.amountCents()));
        view.put("created", c.createdAt().toString());
        view.put("invoice", c.invoiceId() == null ? "none" : c.invoiceId());
        view.put("refunded", c.refunded());
        return view;
    }

    public String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String text(JsonNode in, String field) {
        JsonNode node = in.path(field);
        if (node.isMissingNode() || node.isNull()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return node.asText();
    }

    static long cents(double eur) {
        return Math.round(eur * 100);
    }

    static String eur(long cents) {
        return String.format(Locale.ROOT, "€%d.%02d", cents / 100, cents % 100);
    }

    private static Map<String, Map<String, Object>> props(Object... kv) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) kv[i + 1];
            map.put((String) kv[i], schema);
        }
        return map.isEmpty() ? NO_PROPS : map;
    }
}
