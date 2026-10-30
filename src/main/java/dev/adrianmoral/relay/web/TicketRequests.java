package dev.adrianmoral.relay.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class TicketRequests {

    private TicketRequests() {
    }

    public record NewTicket(@NotBlank @Size(max = 120) String customerName,
                            @NotBlank @Email String customerEmail,
                            @Size(max = 20) String channel,
                            @NotBlank @Size(max = 200) String subject,
                            @NotBlank @Size(max = 6000) String body) {
    }

    public record Approve(@Size(max = 6000) String reply) {
    }

    public record HandOver(@Size(max = 500) String note) {
    }
}
