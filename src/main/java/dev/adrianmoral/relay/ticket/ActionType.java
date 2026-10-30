package dev.adrianmoral.relay.ticket;

/** Tool calls the agent may propose but never run on its own. */
public enum ActionType {
    REFUND,
    ADDRESS_CHANGE,
    SEND_INVOICE
}
