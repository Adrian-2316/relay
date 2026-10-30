package dev.adrianmoral.relay.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

import java.time.Instant;

/** One entry in a ticket's agent run timeline. */
@Entity
public class AgentStep {

    @Id
    @GeneratedValue
    private Long id;
    private String ticketId;
    private int seq;
    @Enumerated(EnumType.STRING)
    private StepKind kind;
    private String title;
    @Column(length = 2000)
    private String note;
    private String tool;
    @Lob
    private String input;
    @Lob
    private String output;
    private long durationMs;
    private Instant createdAt;

    protected AgentStep() {
    }

    public AgentStep(String ticketId, int seq, StepKind kind, String title, String note,
                     String tool, String input, String output, long durationMs) {
        this.ticketId = ticketId;
        this.seq = seq;
        this.kind = kind;
        this.title = title;
        this.note = note;
        this.tool = tool;
        this.input = input;
        this.output = output;
        this.durationMs = durationMs;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTicketId() { return ticketId; }
    public int getSeq() { return seq; }
    public StepKind getKind() { return kind; }
    public String getTitle() { return title; }
    public String getNote() { return note; }
    public String getTool() { return tool; }
    public String getInput() { return input; }
    public String getOutput() { return output; }
    public long getDurationMs() { return durationMs; }
    public Instant getCreatedAt() { return createdAt; }
}
