package dev.adrianmoral.relay.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.adrianmoral.relay.agent.AgentDispatcher;
import dev.adrianmoral.relay.agent.AgentService;
import dev.adrianmoral.relay.ticket.AgentStep;
import dev.adrianmoral.relay.ticket.AgentStepRepository;
import dev.adrianmoral.relay.ticket.PendingActionRepository;
import dev.adrianmoral.relay.ticket.Ticket;
import dev.adrianmoral.relay.ticket.TicketEvents;
import dev.adrianmoral.relay.ticket.TicketRepository;
import dev.adrianmoral.relay.ticket.TicketStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api")
public class TicketController {

    private final TicketRepository tickets;
    private final AgentStepRepository steps;
    private final PendingActionRepository actions;
    private final AgentService agentService;
    private final AgentDispatcher dispatcher;
    private final TicketIntake intake;
    private final TicketEvents events;
    private final ObjectMapper json;
    private final Clock clock;

    public TicketController(TicketRepository tickets, AgentStepRepository steps, PendingActionRepository actions,
                            AgentService agentService, AgentDispatcher dispatcher, TicketIntake intake,
                            TicketEvents events, ObjectMapper json, Clock clock) {
        this.tickets = tickets;
        this.steps = steps;
        this.actions = actions;
        this.agentService = agentService;
        this.dispatcher = dispatcher;
        this.intake = intake;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    @GetMapping("/tickets")
    public List<TicketView> list(@RequestParam(required = false) TicketStatus status) {
        List<Ticket> found = status == null ? tickets.findAllByOrderByReceivedAtDesc()
                : tickets.findByStatusOrderByReceivedAtDesc(status);
        return found.stream().map(t -> view(t, false)).toList();
    }

    @GetMapping("/tickets/{id}")
    public TicketView get(@PathVariable String id) {
        return view(find(id), true);
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketView create(@Valid @RequestBody TicketRequests.NewTicket req) {
        Ticket ticket = intake.receive(req.customerName(), req.customerEmail(), req.channel(), req.subject(), req.body(), clock.instant());
        return view(ticket, false);
    }

    @PostMapping("/tickets/{id}/approve")
    public TicketView approve(@PathVariable String id, @Valid @RequestBody(required = false) TicketRequests.Approve req) {
        return view(agentService.approve(id, req == null ? null : req.reply()), true);
    }

    @PostMapping("/tickets/{id}/hand-over")
    public TicketView handOver(@PathVariable String id, @Valid @RequestBody(required = false) TicketRequests.HandOver req) {
        return view(agentService.handOver(id, req == null ? null : req.note()), true);
    }

    @PostMapping("/tickets/{id}/rerun")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void rerun(@PathVariable String id) {
        agentService.reset(id);
        dispatcher.dispatch(id);
    }

    @GetMapping("/events")
    public SseEmitter events() {
        return events.subscribe();
    }

    private Ticket find(String id) {
        return tickets.findById(id).orElseThrow(() -> new NoSuchElementException("No ticket " + id));
    }

    private TicketView view(Ticket t, boolean detail) {
        List<TicketView.ActionView> actionViews = actions.findByTicketIdOrderById(t.getId()).stream()
                .map(a -> new TicketView.ActionView(a.getId(), a.getType().name(), a.getSummary(), a.getAmountLabel(),
                        a.getDetail(), a.getStatus().name(), a.getResult()))
                .toList();
        List<TicketView.StepView> stepViews = detail
                ? steps.findByTicketIdOrderBySeq(t.getId()).stream().map(this::stepView).toList()
                : null;
        return new TicketView(t.getId(), t.getCustomerName(), t.getCustomerEmail(), t.getChannel(), t.getSubject(),
                t.getBody(), t.getReceivedAt(), t.getStatus(), t.getIntent(), t.getSentiment(), t.getPriority(),
                t.getConfidence(), t.getDraftReply(), t.getEscalationReason(), t.isResolvedByAgent(), actionViews, stepViews);
    }

    private TicketView.StepView stepView(AgentStep s) {
        return new TicketView.StepView(s.getId(), s.getSeq(), s.getKind().name(), s.getTitle(), s.getNote(), s.getTool(),
                parse(s.getInput()), parse(s.getOutput()), s.getDurationMs());
    }

    private JsonNode parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return json.readTree(value);
        } catch (Exception e) {
            return json.getNodeFactory().textNode(value);
        }
    }
}
