package dev.adrianmoral.relay.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import dev.adrianmoral.relay.ticket.Ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Works tickets with Claude through the Messages API, running the tool loop itself. */
public class ClaudeTicketAgent implements TicketAgent {

    static final String SYSTEM_PROMPT = """
            You are the support agent for Northwind Coffee Co., an online shop for specialty coffee and brewing gear.
            You work one customer ticket at a time, with the tools provided, and finish by calling submit_reply or escalate_to_human.

            How to work a ticket:
            - Look facts up before acting. Find the order, charge or shipment the customer means; if they give no order number, list their orders.
            - Before proposing any refund, call check_policy and follow its result. Use the charge id from the order or the charge list, never a guess.
            - issue_refund, update_shipping_address and send_invoice do not run straight away. They are queued, and a person approves them before anything happens. Queue an action only when the facts and the policy support it.
            - Escalate instead of replying when the policy routes the case to a person, when the customer makes a liability or legal claim, when the amount is over €200, or when you are not confident.
            - The reply goes out under the shop's name once approved, so write it as final: in the customer's language, warm and short, stating exactly what was done. Promise only what a queued action or a tool result supports. Sign it "Northwind Support".
            - Never ask the customer for information a tool can give you.
            """;

    private final AnthropicClient client;
    private final SupportTools tools;
    private final AgentProperties props;
    private final List<Tool> toolDefs;

    public ClaudeTicketAgent(AnthropicClient client, SupportTools tools, AgentProperties props) {
        this.client = client;
        this.tools = tools;
        this.props = props;
        this.toolDefs = tools.specs().stream().map(ClaudeTicketAgent::toTool).toList();
    }

    @Override
    public String name() {
        return "claude:" + props.model();
    }

    @Override
    public void work(AgentRun run) {
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.ofUser(describe(run.ticket())));

        for (int turn = 0; turn < props.maxTurns() && !run.finished(); turn++) {
            long start = System.nanoTime();
            Message response = client.messages().create(params(messages));
            long ms = (System.nanoTime() - start) / 1_000_000;

            StopReason stop = response.stopReason().orElse(null);
            if (StopReason.REFUSAL.equals(stop)) {
                throw new AgentException("The model declined this ticket");
            }
            if (StopReason.MAX_TOKENS.equals(stop)) {
                throw new AgentException("The model ran out of output tokens mid-answer");
            }

            String thinking = response.content().stream()
                    .flatMap(b -> b.thinking().stream())
                    .map(t -> t.thinking().strip())
                    .filter(t -> !t.isEmpty())
                    .collect(Collectors.joining(" "));
            run.think(thinking, ms);

            // Pass the assistant turn back unchanged, thinking blocks included.
            messages.add(response.toParam());

            List<ToolUseBlock> calls = response.content().stream().flatMap(b -> b.toolUse().stream()).toList();
            if (calls.isEmpty()) {
                break;
            }
            List<ContentBlockParam> results = new ArrayList<>();
            for (ToolUseBlock call : calls) {
                JsonNode input = call._input().convert(JsonNode.class);
                ToolOutcome outcome = run.call(call.name(), input);
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(call.id())
                        .content(tools.write(outcome.result()))
                        .isError(outcome.error())
                        .build()));
            }
            // All results for one turn go back in a single user message.
            messages.add(MessageParam.ofUserOfContentBlockParams(results));
        }
    }

    private MessageCreateParams params(List<MessageParam> messages) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(props.model())
                .maxTokens(16000L)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(SYSTEM_PROMPT)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(props.effort())).build())
                .tools(toolDefs.stream().map(com.anthropic.models.messages.ToolUnion::ofTool).toList())
                .messages(messages);
        if (props.fallbacks()) {
            // Server-side fallback: if a safety classifier declines, the API retries on a suitable model.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return builder.build();
    }

    static String describe(Ticket t) {
        return """
                New ticket %s
                From: %s <%s>
                Channel: %s
                Received: %s
                Subject: %s

                %s
                """.formatted(t.getId(), t.getCustomerName(), t.getCustomerEmail(), t.getChannel(),
                t.getReceivedAt(), t.getSubject(), t.getBody());
    }

    private static Tool toTool(ToolSpec spec) {
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        spec.properties().forEach((name, schema) -> properties.putAdditionalProperty(name, JsonValue.from(schema)));
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(properties.build())
                        .required(spec.required())
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }
}
