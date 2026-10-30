package dev.adrianmoral.relay.store;

import java.util.Map;

/** The shop's written refund rules, as a tool the agent can check against. */
public final class RefundPolicy {

    public static final long AGENT_LIMIT_CENTS = 20_000;

    private RefundPolicy() {
    }

    public static Map<String, Object> check(String caseType, int daysSinceDelivery, long amountCents) {
        if (amountCents > AGENT_LIMIT_CENTS || "liability".equals(caseType)) {
            return Map.of("eligible", false, "route", "human",
                    "rule", "§7 claims over €200 or liability claims go to a person");
        }
        return switch (caseType) {
            case "damaged" -> daysSinceDelivery <= 30
                    ? Map.of("eligible", true, "resolution", "full refund, no return needed",
                    "rule", "§4.2 damaged on arrival within 30 days")
                    : Map.of("eligible", false, "rule", "§4.2 damage reported after 30 days");
            case "duplicate_charge" -> Map.of("eligible", true, "resolution", "refund the duplicate charge",
                    "rule", "§5.1 duplicate charges are always refunded");
            case "change_of_mind" -> daysSinceDelivery <= 14
                    ? Map.of("eligible", true, "resolution", "refund after return", "rule", "§3 14-day returns")
                    : Map.of("eligible", false, "rule", "§3 return window is 14 days");
            default -> Map.of("eligible", false, "route", "human", "rule", "no matching rule");
        };
    }
}
