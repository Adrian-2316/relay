package dev.adrianmoral.relay.ticket;

public enum TicketStatus {
    /** Received, agent not started yet. */
    NEW,
    /** Agent is working on it. */
    RUNNING,
    /** Agent proposed a guarded action and a draft reply; a person must approve. */
    AWAITING_APPROVAL,
    /** Handed to a person, either by the agent or by an operator. */
    ESCALATED,
    /** Reply sent, by the agent on its own or after approval. */
    RESOLVED
}
