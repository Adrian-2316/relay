package dev.adrianmoral.relay.agent;

/** Works a ticket by calling tools on the run until it submits a reply or escalates. */
public interface TicketAgent {

    void work(AgentRun run);

    String name();
}
