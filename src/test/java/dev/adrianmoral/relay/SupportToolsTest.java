package dev.adrianmoral.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.adrianmoral.relay.agent.SupportTools;
import dev.adrianmoral.relay.agent.ToolOutcome;
import dev.adrianmoral.relay.agent.ToolSpec;
import dev.adrianmoral.relay.store.CommerceStore;
import dev.adrianmoral.relay.ticket.PendingActionRepository;
import dev.adrianmoral.relay.ticket.StepKind;
import dev.adrianmoral.relay.ticket.Ticket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
class SupportToolsTest {

    @Autowired SupportTools tools;
    @Autowired CommerceStore store;
    @Autowired PendingActionRepository actions;
    @Autowired ObjectMapper json;

    private final Ticket laura = new Ticket("T-1", "Laura Gómez", "laura.gomez@example.com", "Email", "s", "b", Instant.now());

    @Test
    void everyToolSchemaIsStrictReady() {
        for (ToolSpec spec : tools.specs()) {
            assertThat(spec.required()).as(spec.name()).containsExactlyElementsOf(spec.properties().keySet());
            assertThat(spec.description()).isNotBlank();
        }
        assertThat(tools.specs()).filteredOn(ToolSpec::guarded).extracting(ToolSpec::name)
                .containsExactlyInAnyOrder("issue_refund", "update_shipping_address", "send_invoice");
    }

    @Test
    void refundOfSomeoneElsesChargeIsRefused() {
        long before = actions.count();
        ToolOutcome outcome = tools.execute(laura, "issue_refund",
                json.valueToTree(Map.of("charge_id", "ch_3Q7aPN81", "amount_eur", 12.0, "reason", "x")));

        assertThat(outcome.error()).isTrue();
        assertThat(actions.count()).isEqualTo(before);
    }

    @Test
    void refundLargerThanTheChargeIsRefused() {
        ToolOutcome outcome = tools.execute(laura, "issue_refund",
                json.valueToTree(Map.of("charge_id", "ch_3Q8xLG21", "amount_eur", 99.0, "reason", "x")));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.note()).contains("€34.90");
    }

    @Test
    void validRefundIsQueuedAndNoMoneyMoves() {
        ToolOutcome outcome = tools.execute(laura, "issue_refund",
                json.valueToTree(Map.of("charge_id", "ch_3Q8xLG21", "amount_eur", 34.90, "reason", "Damaged")));

        assertThat(outcome.kind()).isEqualTo(StepKind.WAIT);
        assertThat(outcome.result()).containsEntry("status", "queued_for_human_approval");
        assertThat(store.refundLog()).isEmpty();
    }

    @Test
    void unknownToolIsReportedToTheModelAsAnError() {
        assertThat(tools.execute(laura, "delete_customer", json.createObjectNode()).error()).isTrue();
    }
}
