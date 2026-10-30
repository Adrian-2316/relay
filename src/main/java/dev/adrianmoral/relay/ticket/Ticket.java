package dev.adrianmoral.relay.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
public class Ticket {

    @Id
    private String id;
    private String customerName;
    private String customerEmail;
    private String channel;
    private String subject;
    @Column(length = 8000)
    private String body;
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    private TicketStatus status = TicketStatus.NEW;

    // Triage written by the agent
    private String intent;
    private String sentiment;
    private String priority;
    private Double confidence;
    @Column(length = 8000)
    private String draftReply;
    @Column(length = 2000)
    private String escalationReason;

    private Instant firstReplyAt;
    private Instant resolvedAt;
    private boolean resolvedByAgent;

    @Version
    private long version;

    protected Ticket() {
    }

    public Ticket(String id, String customerName, String customerEmail, String channel,
                  String subject, String body, Instant receivedAt) {
        this.id = id;
        this.customerName = customerName;
        this.customerEmail = customerEmail;
        this.channel = channel;
        this.subject = subject;
        this.body = body;
        this.receivedAt = receivedAt;
    }

    public void triage(String intent, String sentiment, String priority, double confidence) {
        this.intent = intent;
        this.sentiment = sentiment;
        this.priority = priority;
        this.confidence = confidence;
    }

    public void awaitApproval(String draftReply) {
        this.draftReply = draftReply;
        this.status = TicketStatus.AWAITING_APPROVAL;
    }

    public void escalate(String reason) {
        this.escalationReason = reason;
        this.status = TicketStatus.ESCALATED;
    }

    public void resolve(String reply, boolean byAgent, Instant at) {
        this.draftReply = reply;
        this.status = TicketStatus.RESOLVED;
        this.resolvedByAgent = byAgent;
        this.resolvedAt = at;
        if (firstReplyAt == null) {
            firstReplyAt = at;
        }
    }

    public String getId() { return id; }
    public String getCustomerName() { return customerName; }
    public String getCustomerEmail() { return customerEmail; }
    public String getChannel() { return channel; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Instant getReceivedAt() { return receivedAt; }
    public TicketStatus getStatus() { return status; }
    public void setStatus(TicketStatus status) { this.status = status; }
    public String getIntent() { return intent; }
    public String getSentiment() { return sentiment; }
    public String getPriority() { return priority; }
    public Double getConfidence() { return confidence; }
    public String getDraftReply() { return draftReply; }
    public String getEscalationReason() { return escalationReason; }
    public Instant getFirstReplyAt() { return firstReplyAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public boolean isResolvedByAgent() { return resolvedByAgent; }
}
