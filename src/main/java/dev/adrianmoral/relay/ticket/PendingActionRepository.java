package dev.adrianmoral.relay.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PendingActionRepository extends JpaRepository<PendingAction, Long> {

    List<PendingAction> findByTicketIdOrderById(String ticketId);

    List<PendingAction> findByTicketIdAndStatus(String ticketId, PendingAction.Status status);
}
