package dev.adrianmoral.relay.web;

import dev.adrianmoral.relay.ticket.TicketRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Fills an empty inbox with sample tickets for the fictional Northwind Coffee Co. */
@Component
@ConditionalOnProperty(name = "relay.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DemoSeeder implements ApplicationRunner {

    private final TicketRepository tickets;
    private final TicketIntake intake;
    private final Clock clock;

    public DemoSeeder(TicketRepository tickets, TicketIntake intake, Clock clock) {
        this.tickets = tickets;
        this.intake = intake;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (tickets.count() > 0) {
            return;
        }
        Instant now = clock.instant();
        intake.receive("Elena Petrova", "e.petrova@example.com", "Email", "Where is order 48302?",
                "Hi, order 48302 has said in transit for two days. Any update?", now.minus(Duration.ofMinutes(3)));
        intake.receive("Sophie Laurent", "sophie.laurent@example.com", "Email", "Your descaler damaged my espresso machine",
                "After using your descaling tablets my Rocket Appartamento started leaking. The repair quote is €380. I expect you to cover it.",
                now.minus(Duration.ofMinutes(90)));
        intake.receive("Marco Rossi", "marco@cafferossi.it", "Email", "Invoice with our VAT number, please",
                "Could you reissue the invoice for order 48150 to Caffè Rossi S.r.l., VAT IT03912870165? Our accountant needs it.",
                now.minus(Duration.ofMinutes(55)));
        intake.receive("Priya Nair", "priya.nair@example.com", "Email", "I was charged twice for my bean subscription",
                "My bank shows two €12.00 charges from you yesterday. I only have one subscription. Please fix this.",
                now.minus(Duration.ofMinutes(37)));
        intake.receive("Tom Becker", "tom.becker@example.com", "Chat", "Can I still change my delivery address?",
                "I just moved and realised order 48377 is going to my old flat in Mitte. My new address is Oranienstraße 21, 10999 Berlin. Can you change it?",
                now.minus(Duration.ofMinutes(11)));
        intake.receive("Laura Gómez", "laura.gomez@example.com", "Email", "Order #48213 arrived with a cracked dripper",
                "Hi, my pour-over set arrived this morning and the ceramic dripper is cracked right through. The box was dented too.\n\nI bought it as a gift for Saturday. Can you send a new one or refund me? Photos attached.\n\nLaura",
                now.minus(Duration.ofMinutes(2)));
    }
}
