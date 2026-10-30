package dev.adrianmoral.relay.web;

import dev.adrianmoral.relay.agent.AgentService;
import dev.adrianmoral.relay.ticket.Ticket;
import dev.adrianmoral.relay.ticket.TicketRepository;
import dev.adrianmoral.relay.ticket.TicketStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

@RestController
public class StatsController {

    private final TicketRepository tickets;
    private final AgentService agentService;

    public StatsController(TicketRepository tickets, AgentService agentService) {
        this.tickets = tickets;
        this.agentService = agentService;
    }

    public record Stats(String agent, long total, long awaitingApproval, long escalated, long resolved,
                        int autoResolvedPercent, Long medianFirstReplySeconds) {
    }

    @GetMapping("/api/stats")
    public Stats stats() {
        List<Ticket> all = tickets.findAll();
        long resolved = all.stream().filter(t -> t.getStatus() == TicketStatus.RESOLVED).count();
        long byAgent = all.stream().filter(Ticket::isResolvedByAgent).count();
        List<Long> replySeconds = all.stream()
                .map(t -> t.getFirstReplyAt() == null ? null : Duration.between(t.getReceivedAt(), t.getFirstReplyAt()).toSeconds())
                .filter(Objects::nonNull).sorted().toList();
        return new Stats(agentService.agentName(), all.size(),
                tickets.countByStatus(TicketStatus.AWAITING_APPROVAL), tickets.countByStatus(TicketStatus.ESCALATED), resolved,
                resolved == 0 ? 0 : (int) Math.round(100.0 * byAgent / resolved),
                replySeconds.isEmpty() ? null : replySeconds.get(replySeconds.size() / 2));
    }
}
