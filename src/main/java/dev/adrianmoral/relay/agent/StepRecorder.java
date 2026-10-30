package dev.adrianmoral.relay.agent;

import dev.adrianmoral.relay.ticket.AgentStep;
import dev.adrianmoral.relay.ticket.AgentStepRepository;
import dev.adrianmoral.relay.ticket.StepKind;
import dev.adrianmoral.relay.ticket.TicketEvents;
import org.springframework.stereotype.Component;

@Component
public class StepRecorder {

    private final AgentStepRepository steps;
    private final TicketEvents events;

    public StepRecorder(AgentStepRepository steps, TicketEvents events) {
        this.steps = steps;
        this.events = events;
    }

    public AgentStep record(String ticketId, StepKind kind, String title, String note,
                            String tool, String input, String output, long durationMs) {
        int seq = steps.countByTicketId(ticketId) + 1;
        AgentStep step = steps.save(new AgentStep(ticketId, seq, kind, title, note, tool, input, output, durationMs));
        events.stepAdded(ticketId, step.getId());
        return step;
    }
}
