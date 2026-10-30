package dev.adrianmoral.relay.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param mode             claude, scripted, or auto (Claude when an API key is set, scripted otherwise)
 * @param effort           low, medium, high, xhigh or max
 * @param fallbacks        let the API retry a refused request on another model
 * @param autoSendReadOnly send replies straight away when the agent queued no guarded action
 */
@ConfigurationProperties("relay.agent")
public record AgentProperties(
        @DefaultValue("auto") String mode,
        @DefaultValue("claude-opus-5-5") String model,
        @DefaultValue("medium") String effort,
        @DefaultValue("12") int maxTurns,
        @DefaultValue("true") boolean fallbacks,
        @DefaultValue("true") boolean autoSendReadOnly) {
}
