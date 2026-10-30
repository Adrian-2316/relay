package dev.adrianmoral.relay;

import dev.adrianmoral.relay.agent.AgentService;
import dev.adrianmoral.relay.store.CommerceStore;
import dev.adrianmoral.relay.ticket.AgentStepRepository;
import dev.adrianmoral.relay.ticket.PendingAction;
import dev.adrianmoral.relay.ticket.PendingActionRepository;
import dev.adrianmoral.relay.ticket.StepKind;
import dev.adrianmoral.relay.ticket.Ticket;
import dev.adrianmoral.relay.ticket.TicketRepository;
import dev.adrianmoral.relay.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "OPERATOR")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ApprovalFlowTest {

    @Autowired MockMvc mvc;
    @Autowired AgentService agentService;
    @Autowired TicketRepository tickets;
    @Autowired AgentStepRepository steps;
    @Autowired PendingActionRepository actions;
    @Autowired CommerceStore store;

    private String ticket(String name, String email, String subject, String body) {
        String id = "T-" + UUID.randomUUID().toString().substring(0, 6);
        tickets.save(new Ticket(id, name, email, "Email", subject, body, Instant.now()));
        return id;
    }

    @Test
    void refundIsQueuedNotRunUntilAPersonApproves() throws Exception {
        String id = ticket("Laura Gómez", "laura.gomez@example.com", "Order #48213 arrived cracked",
                "The ceramic dripper is cracked right through.");

        agentService.run(id);

        assertThat(tickets.findById(id).orElseThrow().getStatus()).isEqualTo(TicketStatus.AWAITING_APPROVAL);
        assertThat(actions.findByTicketIdAndStatus(id, PendingAction.Status.PENDING)).hasSize(1);
        assertThat(store.refundLog()).as("no money moves before approval").isEmpty();
        assertThat(steps.findByTicketIdOrderBySeq(id)).extracting(s -> s.getKind())
                .contains(StepKind.THINK, StepKind.TOOL, StepKind.WAIT);

        mvc.perform(post("/api/tickets/{id}/approve", id).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reply\":\"Hi Laura, refunded. Northwind Support\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.reply").value("Hi Laura, refunded. Northwind Support"))
                .andExpect(jsonPath("$.actions[0].status").value("APPROVED"));

        assertThat(store.refundLog()).hasSize(1).first().asString().contains("ch_3Q8xLG21:3490");

        mvc.perform(post("/api/tickets/{id}/approve", id).with(csrf())).andExpect(status().isConflict());
    }

    @Test
    void duplicateChargeRefundsOnlyTheChargeWithoutInvoice() {
        String id = ticket("Priya Nair", "priya.nair@example.com", "Charged twice",
                "My bank shows two €12.00 charges from you yesterday.");

        agentService.run(id);
        agentService.approve(id, null);

        assertThat(store.refundLog()).singleElement().asString().startsWith("re_").contains("ch_3Q7aPN82:1200");
    }

    @Test
    void liabilityClaimIsEscalatedWithNothingQueued() {
        String id = ticket("Sophie Laurent", "sophie.laurent@example.com", "Descaler damaged my machine",
                "My espresso machine leaks. The repair quote is €380. I expect compensation.");

        agentService.run(id);

        Ticket t = tickets.findById(id).orElseThrow();
        assertThat(t.getStatus()).isEqualTo(TicketStatus.ESCALATED);
        assertThat(t.getEscalationReason()).contains("€380");
        assertThat(actions.findByTicketIdOrderById(id)).isEmpty();
    }

    @Test
    void readOnlyAnswersAreSentWithoutApproval() {
        String id = ticket("Jonas Weber", "jonas.weber@example.com", "Where is my order?",
                "Order 48302 still says in transit, any update?");

        agentService.run(id);

        Ticket t = tickets.findById(id).orElseThrow();
        assertThat(t.getStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(t.isResolvedByAgent()).isTrue();
        assertThat(t.getDraftReply()).contains("JD0146000302");
    }

    @Test
    void handOverCancelsQueuedActions() {
        String id = ticket("Tom Becker", "tom.becker@example.com", "Change address",
                "Order 48377 please, my new address is Oranienstraße 21, 10999 Berlin.");
        agentService.run(id);

        agentService.handOver(id, "Customer called, handling by phone");

        assertThat(tickets.findById(id).orElseThrow().getStatus()).isEqualTo(TicketStatus.ESCALATED);
        assertThat(actions.findByTicketIdOrderById(id)).allMatch(a -> a.getStatus() == PendingAction.Status.REJECTED);
    }

    @Test
    void newTicketOverHttpIsWorkedInTheBackground() throws Exception {
        mvc.perform(post("/api/tickets").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerName":"Marco Rossi","customerEmail":"marco@cafferossi.it","subject":"Invoice with VAT",
                         "body":"Please reissue order 48150 to Caffè Rossi S.r.l., VAT IT03912870165."}"""))
                .andExpect(status().isCreated());

        String id = tickets.findAll().getFirst().getId();
        await().until(() -> tickets.findById(id).orElseThrow().getStatus() == TicketStatus.AWAITING_APPROVAL);

        mvc.perform(get("/api/tickets/{id}", id))
                .andExpect(jsonPath("$.actions[0].type").value("SEND_INVOICE"))
                .andExpect(jsonPath("$.steps[0].kind").value("THINK"))
                .andExpect(jsonPath("$.steps[1].tool").value("validate_vat"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void approvingWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post("/api/tickets/{id}/approve", "T-1")).andExpect(status().isForbidden());
    }

    @Test
    @org.springframework.security.test.context.support.WithAnonymousUser
    void apiNeedsAnOperatorLogin() throws Exception {
        mvc.perform(get("/api/tickets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void rejectsInvalidNewTicket() throws Exception {
        mvc.perform(post("/api/tickets").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"\",\"customerEmail\":\"nope\",\"subject\":\"x\",\"body\":\"y\"}"))
                .andExpect(status().isBadRequest());
    }
}
