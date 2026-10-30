package dev.adrianmoral.relay.ticket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Pushes ticket and step changes to every open inbox over server-sent events. */
@Component
public class TicketEvents {

    private static final Logger log = LoggerFactory.getLogger(TicketEvents.class);

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public void ticketChanged(String ticketId) {
        send("ticket", Map.of("ticketId", ticketId));
    }

    public void stepAdded(String ticketId, long stepId) {
        send("step", Map.of("ticketId", ticketId, "stepId", stepId));
    }

    private void send(String name, Object data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping closed event stream: {}", e.getMessage());
                emitters.remove(emitter);
            }
        }
    }
}
