package dev.adrianmoral.relay.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.adrianmoral.relay.ticket.StepKind;
import dev.adrianmoral.relay.ticket.Ticket;

/**
 * One agent run over one ticket. Agents call tools through it so every call is timed,
 * recorded on the timeline and passed through the approval gate the same way.
 */
public class AgentRun {

    private final Ticket ticket;
    private final SupportTools tools;
    private final StepRecorder recorder;
    private JsonNode reply;
    private JsonNode escalation;
    private int thoughts;

    AgentRun(Ticket ticket, SupportTools tools, StepRecorder recorder) {
        this.ticket = ticket;
        this.tools = tools;
        this.recorder = recorder;
    }

    public Ticket ticket() {
        return ticket;
    }

    public ToolOutcome call(String name, JsonNode input) {
        long start = System.nanoTime();
        ToolOutcome outcome = tools.execute(ticket, name, input);
        long ms = (System.nanoTime() - start) / 1_000_000;
        recorder.record(ticket.getId(), outcome.kind(), outcome.title(), outcome.note(), name,
                tools.write(input), tools.write(outcome.result()), ms);
        if (!outcome.error()) {
            if (SupportTools.SUBMIT_REPLY.equals(name)) {
                reply = input;
            } else if (SupportTools.ESCALATE.equals(name)) {
                escalation = input;
            }
        }
        return outcome;
    }

    /** Records the model's reasoning summary as a timeline step. */
    public void think(String summary, long ms) {
        if (summary == null || summary.isBlank()) {
            return;
        }
        String note = summary.length() > 420 ? summary.substring(0, 417).stripTrailing() + "…" : summary;
        recorder.record(ticket.getId(), StepKind.THINK, thoughts++ == 0 ? "Read the ticket" : "Reasoned about the next step",
                note, null, null, null, ms);
    }

    public boolean finished() {
        return reply != null || escalation != null;
    }

    public JsonNode reply() {
        return reply;
    }

    public JsonNode escalation() {
        return escalation;
    }
}
