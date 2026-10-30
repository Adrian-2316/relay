package dev.adrianmoral.relay.web;

import dev.adrianmoral.relay.agent.AgentDispatcher;
import dev.adrianmoral.relay.ticket.Ticket;
import dev.adrianmoral.relay.ticket.TicketEvents;
import dev.adrianmoral.relay.ticket.TicketRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/** Receives new tickets (from email, chat or the demo form) and hands them to the agent. */
@Service
public class TicketIntake {

    private final TicketRepository tickets;
    private final AgentDispatcher dispatcher;
    private final TicketEvents events;
    private final AtomicInteger next = new AtomicInteger(-1);

    public TicketIntake(TicketRepository tickets, AgentDispatcher dispatcher, TicketEvents events) {
        this.tickets = tickets;
        this.dispatcher = dispatcher;
        this.events = events;
    }

    public Ticket receive(String name, String email, String channel, String subject, String body, Instant at) {
        Ticket ticket = tickets.save(new Ticket(nextId(), name, email, channel == null || channel.isBlank() ? "Email" : channel,
                subject, body, at));
        events.ticketChanged(ticket.getId());
        dispatcher.dispatch(ticket.getId());
        return ticket;
    }

    private synchronized String nextId() {
        if (next.get() < 0) {
            int max = tickets.findAll().stream()
                    .map(t -> t.getId().replace("T-", ""))
                    .filter(s -> s.matches("\\d+"))
                    .mapToInt(Integer::parseInt).max().orElse(2278);
            next.set(max + 1);
        }
        return "T-" + next.getAndIncrement();
    }
}
