package dev.adrianmoral.relay.agent;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Starts agent runs off the request thread so the inbox can watch them live. */
@Component
public class AgentDispatcher {

    private final AgentService agentService;

    public AgentDispatcher(AgentService agentService) {
        this.agentService = agentService;
    }

    @Async
    public void dispatch(String ticketId) {
        agentService.run(ticketId);
    }
}
