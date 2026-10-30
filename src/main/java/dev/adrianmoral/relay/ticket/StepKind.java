package dev.adrianmoral.relay.ticket;

public enum StepKind {
    /** The agent's reasoning summary. */
    THINK,
    /** A read-only tool call that ran. */
    TOOL,
    /** A guarded tool call that is waiting for a person. */
    WAIT,
    /** The agent handed the ticket to a person. */
    STOP,
    /** A final step: reply sent or action approved. */
    DONE
}
