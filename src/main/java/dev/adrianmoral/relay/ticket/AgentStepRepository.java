package dev.adrianmoral.relay.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentStepRepository extends JpaRepository<AgentStep, Long> {

    List<AgentStep> findByTicketIdOrderBySeq(String ticketId);

    int countByTicketId(String ticketId);

    void deleteByTicketId(String ticketId);
}
