package dev.adrianmoral.relay.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.adrianmoral.relay.ticket.Ticket;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rule-based stand-in for the model, used when no API key is configured and in tests.
 * It goes through the same tools and approval gate as {@link ClaudeTicketAgent}.
 */
public class ScriptedTicketAgent implements TicketAgent {

    private static final Pattern ORDER = Pattern.compile("\\b(4\\d{4})\\b");
    private static final Pattern VAT = Pattern.compile("\\b([A-Z]{2}[0-9A-Z]{8,12})\\b");
    private static final Pattern NEW_ADDRESS = Pattern.compile("(?i)new address is ([^\\n]+?\\d{4,5} [^.\\n]+)");
    private static final Pattern EUROS = Pattern.compile("€\\s?(\\d+(?:[.,]\\d{1,2})?)");
    private static final Pattern COMPANY = Pattern.compile("to ([A-Z][^,]+?(?:S\\.r\\.l\\.|S\\.L\\.|GmbH|Ltd|SL|SA))");

    private final ObjectMapper json;

    public ScriptedTicketAgent(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String name() {
        return "scripted";
    }

    @Override
    public void work(AgentRun run) {
        Ticket t = run.ticket();
        String raw = t.getSubject() + "\n" + t.getBody();
        String text = raw.toLowerCase(Locale.ROOT);
        String order = find(ORDER, raw);

        if (text.matches("(?s).*(compensation|repair|lawyer|machine).*")) {
            liabilityClaim(run, raw);
        } else if (order != null && text.matches("(?s).*(damaged|cracked|broken|smashed).*")) {
            damaged(run, order);
        } else if (text.matches("(?s).*(charged twice|double charge|two .*charges|duplicate).*")) {
            duplicateCharge(run);
        } else if (order != null && text.contains("address")) {
            addressChange(run, order, raw);
        } else if (text.matches("(?s).*(invoice|vat).*") && find(VAT, raw) != null) {
            invoice(run, order, raw);
        } else if (order != null && text.matches("(?s).*(where is|in transit|tracking|update).*")) {
            tracking(run, order);
        } else {
            run.think("The request doesn't match a case I can resolve with the shop's tools, so a person should take it.", 300);
            run.call(SupportTools.ESCALATE, in("reason", "No matching playbook", "summary", t.getSubject(), "team", "general"));
        }
    }

    private void damaged(AgentRun run, String order) {
        run.think("The customer says order " + order + " arrived damaged. I'll check the order and the damage policy before proposing a refund.", 410);
        Map<String, Object> o = run.call("get_order", in("order_id", order)).result();
        double total = euros((String) o.get("total"));
        int days = ((Number) o.getOrDefault("days_since_delivery", 0)).intValue();
        Map<String, Object> policy = run.call("check_policy", in("case_type", "damaged", "days_since_delivery", days, "amount_eur", total)).result();
        if (!Boolean.TRUE.equals(policy.get("eligible"))) {
            run.call(SupportTools.ESCALATE, in("reason", String.valueOf(policy.get("rule")), "summary", "Damaged item outside the refund policy", "team", "claims"));
            return;
        }
        run.call("issue_refund", in("charge_id", o.get("charge_id"), "amount_eur", total, "reason", "Damaged on arrival"));
        String first = firstName(run.ticket());
        run.call(SupportTools.SUBMIT_REPLY, in("intent", "refund_request", "sentiment", "frustrated", "priority", "high", "confidence", 0.97,
                "reply", "Hi " + first + ",\n\nI'm sorry your " + o.get("item") + " arrived damaged. I've refunded the full "
                        + o.get("total") + " to your card, and you don't need to send anything back. It should show within 5 to 10 days.\n\nNorthwind Support"));
    }

    private void duplicateCharge(AgentRun run) {
        Ticket t = run.ticket();
        run.think("The customer reports a double charge. I'll list their recent charges and look for one without an invoice.", 350);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> charges = (List<Map<String, Object>>) run.call("list_charges",
                in("customer_email", t.getCustomerEmail(), "days", 7)).result().get("charges");
        Map<String, Object> duplicate = charges.stream()
                .filter(c -> "none".equals(c.get("invoice")) && !Boolean.TRUE.equals(c.get("refunded")))
                .filter(c -> charges.stream().anyMatch(o -> o != c && Objects.equals(o.get("amount"), c.get("amount"))))
                .findFirst().orElse(null);
        if (duplicate == null) {
            run.call(SupportTools.ESCALATE, in("reason", "No duplicate charge found", "summary", "Customer reports a double charge we can't see", "team", "billing"));
            return;
        }
        double amount = euros((String) duplicate.get("amount"));
        run.call("check_policy", in("case_type", "duplicate_charge", "days_since_delivery", 0, "amount_eur", amount));
        run.call("issue_refund", in("charge_id", duplicate.get("charge_id"), "amount_eur", amount, "reason", "Duplicate charge with no invoice"));
        run.call(SupportTools.SUBMIT_REPLY, in("intent", "duplicate_charge", "sentiment", "frustrated", "priority", "high", "confidence", 0.99,
                "reply", "Hi " + firstName(t) + ",\n\nYou're right, a payment retry on our side charged you twice. I've refunded the extra "
                        + duplicate.get("amount") + ", and your subscription carries on as normal.\n\nSorry for the hassle.\n\nNorthwind Support"));
    }

    private void addressChange(AgentRun run, String order, String raw) {
        String address = find(NEW_ADDRESS, raw);
        run.think("The customer wants order " + order + " sent to a new address. It can only be changed before the carrier picks it up.", 380);
        run.call("get_order", in("order_id", order));
        if (address == null) {
            run.call(SupportTools.ESCALATE, in("reason", "Couldn't read the new address", "summary", "Address change for order " + order, "team", "general"));
            return;
        }
        run.call("validate_address", in("address", address));
        ToolOutcome change = run.call("update_shipping_address", in("order_id", order, "new_address", address));
        if (change.error()) {
            run.call(SupportTools.ESCALATE, in("reason", change.note(), "summary", "Address change for order " + order, "team", "general"));
            return;
        }
        run.call(SupportTools.SUBMIT_REPLY, in("intent", "change_address", "sentiment", "neutral", "priority", "medium", "confidence", 0.94,
                "reply", "Hi " + firstName(run.ticket()) + ",\n\nGood timing, the parcel hasn't left our warehouse yet. Order " + order
                        + " will now go to " + address + ", and you'll get the updated tracking link by email tonight.\n\nNorthwind Support"));
    }

    private void invoice(AgentRun run, String order, String raw) {
        String vat = find(VAT, raw);
        run.think("The customer needs a business invoice with VAT number " + vat + ". I'll validate it in VIES first.", 300);
        Map<String, Object> check = run.call("validate_vat", in("vat_number", vat)).result();
        if (!Boolean.TRUE.equals(check.get("valid")) || order == null) {
            run.call(SupportTools.ESCALATE, in("reason", "VAT number or order could not be verified", "summary", "Invoice request", "team", "billing"));
            return;
        }
        String company = Objects.requireNonNullElse(find(COMPANY, raw), (String) check.get("name"));
        run.call("send_invoice", in("order_id", order, "company_name", company, "vat_number", vat));
        run.call(SupportTools.SUBMIT_REPLY, in("intent", "business_invoice", "sentiment", "neutral", "priority", "low", "confidence", 0.96,
                "reply", "Hi " + firstName(run.ticket()) + ",\n\nAttached is the corrected invoice for order " + order + ", issued to "
                        + company + " (" + vat + ") with the EU reverse charge applied.\n\nNorthwind Support"));
    }

    private void tracking(AgentRun run, String order) {
        run.think("The customer asks where order " + order + " is. A tracking lookup answers it, no changes needed.", 220);
        Map<String, Object> track = run.call("track_shipment", in("order_id", order)).result();
        run.call(SupportTools.SUBMIT_REPLY, in("intent", "order_status", "sentiment", "neutral", "priority", "low", "confidence", 0.99,
                "reply", "Hi " + firstName(run.ticket()) + ",\n\nYour order " + order + " is " + String.valueOf(track.get("status")).replace('_', ' ')
                        + " with " + track.get("carrier") + ", expected " + track.get("eta") + ". Tracking number: " + track.get("tracking") + ".\n\nNorthwind Support"));
    }

    private void liabilityClaim(AgentRun run, String raw) {
        String amount = find(EUROS, raw);
        double eur = amount == null ? 0 : Double.parseDouble(amount.replace(',', '.'));
        run.think("This is a damage claim against the shop" + (amount == null ? "" : " for €" + amount) + ". Liability claims are outside what I may settle.", 420);
        run.call("check_policy", in("case_type", "liability", "days_since_delivery", 0, "amount_eur", eur));
        run.call(SupportTools.ESCALATE, in("reason", "Liability claim" + (amount == null ? "" : " of €" + amount) + ", above the agent's limit",
                "summary", "Customer says our product damaged their equipment and asks for compensation. No reply sent yet.", "team", "claims"));
    }

    private JsonNode in(Object... kv) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return json.valueToTree(map);
    }

    private static String find(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static double euros(String label) {
        return Double.parseDouble(label.replace("€", ""));
    }

    private static String firstName(Ticket t) {
        return t.getCustomerName().split(" ")[0];
    }
}
