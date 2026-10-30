package dev.adrianmoral.relay.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.adrianmoral.relay.ticket.AgentStepRepository;
import dev.adrianmoral.relay.ticket.PendingAction;
import dev.adrianmoral.relay.ticket.PendingActionRepository;
import dev.adrianmoral.relay.ticket.StepKind;
import dev.adrianmoral.relay.ticket.Ticket;
import dev.adrianmoral.relay.ticket.TicketEvents;
import dev.adrianmoral.relay.ticket.TicketRepository;
import dev.adrianmoral.relay.ticket.TicketStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/** Runs the agent on a ticket and applies a person's decision afterwards. */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final TicketAgent agent;
    private final SupportTools tools;
    private final StepRecorder recorder;
    private final TicketRepository tickets;
    private final AgentStepRepository steps;
    private final PendingActionRepository actions;
    private final TicketEvents events;
    private final AgentProperties props;
    private final Clock clock;

    public AgentService(TicketAgent agent, SupportTools tools, StepRecorder recorder, TicketRepository tickets,
                        AgentStepRepository steps, PendingActionRepository actions, TicketEvents events,
                        AgentProperties props, Clock clock) {
        this.agent = agent;
        this.tools = tools;
        this.recorder = recorder;
        this.tickets = tickets;
        this.steps = steps;
        this.actions = actions;
        this.events = events;
        this.props = props;
        this.clock = clock;
    }

    public String agentName() {
        return agent.name();
    }

    /** Works a ticket from start to a decision: auto-reply, waiting for approval, or escalated. */
    public void run(String ticketId) {
        Ticket ticket = load(ticketId);
        ticket.setStatus(TicketStatus.RUNNING);
        ticket = save(ticket);

        AgentRun run = new AgentRun(ticket, tools, recorder);
        try {
            agent.work(run);
        } catch (RuntimeException e) {
            log.warn("Agent failed on {}: {}", ticketId, e.toString());
            recorder.record(ticketId, StepKind.STOP, "Agent stopped", e.getMessage(), null, null, null, 0);
        }

        ticket = load(ticketId);
        JsonNode reply = run.reply();
        if (reply != null) {
            ticket.triage(reply.path("intent").asText(), reply.path("sentiment").asText(),
                    reply.path("priority").asText(), reply.path("confidence").asDouble());
        }
        boolean queued = !actions.findByTicketIdAndStatus(ticketId, PendingAction.Status.PENDING).isEmpty();

        if (run.escalation() != null) {
            rejectPending(ticketId, "Ticket escalated");
            ticket.escalate(run.escalation().path("summary").asText() + " (" + run.escalation().path("reason").asText() + ")");
        } else if (reply == null) {
            rejectPending(ticketId, "Agent did not finish");
            ticket.escalate("The agent finished without a decision");
            recorder.record(ticketId, StepKind.STOP, "Handed over to a person", "No reply or decision from the agent", null, null, null, 0);
        } else if (queued || !props.autoSendReadOnly()) {
            ticket.awaitApproval(reply.path("reply").asText());
        } else {
            ticket.resolve(reply.path("reply").asText(), true, clock.instant());
            recorder.record(ticketId, StepKind.DONE, "Replied automatically", "Read-only answer, sent without approval", null, null, null, 0);
        }
        save(ticket);
    }

    /** A person approved the ticket: run the queued actions, then send the (possibly edited) reply. */
    @Transactional
    public Ticket approve(String ticketId, String reply) {
        Ticket ticket = load(ticketId);
        if (ticket.getStatus() != TicketStatus.AWAITING_APPROVAL) {
            throw new IllegalStateException("Ticket " + ticketId + " is not waiting for approval");
        }
        List<String> done = new ArrayList<>();
        for (PendingAction action : actions.findByTicketIdAndStatus(ticketId, PendingAction.Status.PENDING)) {
            String result = tools.perform(action);
            action.decide(PendingAction.Status.APPROVED, result, clock.instant());
            done.add(result);
        }
        String text = reply == null || reply.isBlank() ? ticket.getDraftReply() : reply;
        ticket.resolve(text, false, clock.instant());
        recorder.record(ticketId, StepKind.DONE, "Approved and sent",
                done.isEmpty() ? "Reply sent" : String.join(" · ", done), null, null, null, 0);
        return save(ticket);
    }

    /** A person took the ticket over instead: nothing queued will run. */
    @Transactional
    public Ticket handOver(String ticketId, String note) {
        Ticket ticket = load(ticketId);
        if (ticket.getStatus() == TicketStatus.RESOLVED) {
            throw new IllegalStateException("Ticket " + ticketId + " is already resolved");
        }
        rejectPending(ticketId, "Handed to a person");
        ticket.escalate(note == null || note.isBlank() ? "Sent to a person by the operator" : note);
        recorder.record(ticketId, StepKind.STOP, "Sent to a person", "Queued actions cancelled", null, null, null, 0);
        return save(ticket);
    }

    /** Clears a ticket's run so the agent can work it again. */
    @Transactional
    public void reset(String ticketId) {
        Ticket ticket = load(ticketId);
        if (ticket.getStatus() == TicketStatus.RESOLVED || ticket.getStatus() == TicketStatus.RUNNING) {
            throw new IllegalStateException("Ticket " + ticketId + " can't be re-run now");
        }
        rejectPending(ticketId, "Re-run");
        steps.deleteByTicketId(ticketId);
        ticket.setStatus(TicketStatus.NEW);
        save(ticket);
    }

    private void rejectPending(String ticketId, String why) {
        for (PendingAction action : actions.findByTicketIdAndStatus(ticketId, PendingAction.Status.PENDING)) {
            action.decide(PendingAction.Status.REJECTED, why, clock.instant());
            actions.save(action);
        }
    }

    private Ticket load(String id) {
        return tickets.findById(id).orElseThrow(() -> new NoSuchElementException("No ticket " + id));
    }

    private Ticket save(Ticket ticket) {
        Ticket saved = tickets.save(ticket);
        events.ticketChanged(saved.getId());
        return saved;
    }
}
