package dev.adrianmoral.relay.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import dev.adrianmoral.relay.ticket.TicketStatus;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TicketView(String id, String customerName, String customerEmail, String channel, String subject,
                         String body, Instant receivedAt, TicketStatus status, String intent, String sentiment,
                         String priority, Double confidence, String reply, String escalationReason,
                         boolean resolvedByAgent, List<ActionView> actions, List<StepView> steps) {

    public record ActionView(long id, String type, String summary, String amount, String detail, String status,
                             String result) {
    }

    public record StepView(long id, int seq, String kind, String title, String note, String tool, JsonNode input,
                           JsonNode output, long durationMs) {
    }
}
