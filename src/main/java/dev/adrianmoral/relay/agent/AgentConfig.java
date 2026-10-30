package dev.adrianmoral.relay.agent;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TicketAgent ticketAgent(AgentProperties props, SupportTools tools, ObjectMapper json) {
        boolean hasKey = System.getenv("ANTHROPIC_API_KEY") != null || System.getenv("ANTHROPIC_AUTH_TOKEN") != null;
        boolean useClaude = switch (props.mode()) {
            case "claude" -> true;
            case "scripted" -> false;
            default -> hasKey;
        };
        if (!useClaude) {
            log.info("Relay agent: scripted mode. Set ANTHROPIC_API_KEY to let Claude work the tickets.");
            return new ScriptedTicketAgent(json);
        }
        log.info("Relay agent: Claude ({}, effort {})", props.model(), props.effort());
        return new ClaudeTicketAgent(AnthropicOkHttpClient.fromEnv(), tools, props);
    }
}
