package dev.adrianmoral.relay.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, String> {

    List<Ticket> findAllByOrderByReceivedAtDesc();

    List<Ticket> findByStatusOrderByReceivedAtDesc(TicketStatus status);

    long countByStatus(TicketStatus status);
}
