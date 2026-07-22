package dev.reckon.command.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.reckon.command.eventstore.EventStore;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes an account's raw event stream — the audit log itself.
 *
 * <p>This is a read on the write side, and deliberately so: the events <em>are</em> the
 * source of truth, and exposing them is not the same as serving a derived read model
 * (that stays the query service's job). It is what makes "the audit log is free with event
 * sourcing" concrete — there is nothing to build, the log is already the storage model.
 *
 * <p>The dashboard's replay viewer reads this and folds the events client-side to
 * reconstruct the balance, showing that balance is a computation over the log, not a
 * stored number.
 */
@RestController
@RequestMapping("/accounts")
class EventStreamController {

    private final EventStore eventStore;
    private final ObjectMapper objectMapper;

    EventStreamController(EventStore eventStore, ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{accountId}/events")
    List<EventView> events(@PathVariable String accountId) {
        return eventStore.loadStream(accountId).stream()
                .map(stored -> new EventView(
                        stored.eventId().toString(),
                        stored.version(),
                        stored.eventType(),
                        parse(stored.payloadJson()),
                        stored.occurredAt().toString()))
                .toList();
    }

    private JsonNode parse(String payloadJson) {
        try {
            return objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            throw new IllegalStateException("could not parse stored payload: " + payloadJson, e);
        }
    }

    /** One event, as the audit log holds it: envelope fields plus the parsed payload. */
    record EventView(String eventId, long version, String eventType, JsonNode payload, String occurredAt) {}
}
