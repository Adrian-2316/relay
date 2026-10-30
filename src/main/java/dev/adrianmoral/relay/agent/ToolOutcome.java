package dev.adrianmoral.relay.agent;

import dev.adrianmoral.relay.ticket.StepKind;

import java.util.Map;

/**
 * What a tool call produced: the result handed back to the model, and how it shows on the timeline.
 *
 * @param error    true when the call was refused or failed; the model sees it as an error result
 * @param terminal true for submit_reply and escalate_to_human, which end the run
 */
public record ToolOutcome(StepKind kind, String title, String note, Map<String, Object> result,
                          boolean error, boolean terminal) {

    static ToolOutcome ran(String title, Map<String, Object> result) {
        return new ToolOutcome(StepKind.TOOL, title, null, result, false, false);
    }

    static ToolOutcome refused(String title, String reason) {
        return new ToolOutcome(StepKind.TOOL, title, reason, Map.of("error", reason), true, false);
    }
}
