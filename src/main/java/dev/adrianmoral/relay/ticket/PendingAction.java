package dev.adrianmoral.relay.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

import java.time.Instant;

/** A guarded tool call held back until a person approves it. */
@Entity
public class PendingAction {

    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue
    private Long id;
    private String ticketId;
    @Enumerated(EnumType.STRING)
    private ActionType type;
    @Column(length = 4000)
    private String payload;
    private String summary;
    private String amountLabel;
    @Column(length = 1000)
    private String detail;
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;
    @Column(length = 2000)
    private String result;
    private Instant decidedAt;

    protected PendingAction() {
    }

    public PendingAction(String ticketId, ActionType type, String payload, String summary,
                         String amountLabel, String detail) {
        this.ticketId = ticketId;
        this.type = type;
        this.payload = payload;
        this.summary = summary;
        this.amountLabel = amountLabel;
        this.detail = detail;
    }

    public void decide(Status status, String result, Instant at) {
        this.status = status;
        this.result = result;
        this.decidedAt = at;
    }

    public Long getId() { return id; }
    public String getTicketId() { return ticketId; }
    public ActionType getType() { return type; }
    public String getPayload() { return payload; }
    public String getSummary() { return summary; }
    public String getAmountLabel() { return amountLabel; }
    public String getDetail() { return detail; }
    public Status getStatus() { return status; }
    public String getResult() { return result; }
    public Instant getDecidedAt() { return decidedAt; }
}
