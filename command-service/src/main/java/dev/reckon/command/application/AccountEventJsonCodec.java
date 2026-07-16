package dev.reckon.command.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.reckon.command.domain.account.AccountEvent;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Translates {@link AccountEvent} to and from the {@code (event_type, payload)} pair the
 * store holds.
 */
@Component
public class AccountEventJsonCodec {

    /**
     * The event-type registry, written out by hand on purpose.
     *
     * <p>These strings are a <b>permanent contract</b>. They are what lets an event
     * written today be read back in five years, and read models are rebuilt by replaying
     * every one of them. Deriving the name from {@code getClass().getSimpleName()} would
     * work perfectly until someone renamed a record in their IDE — at which point every
     * historical event of that type becomes unreadable and the log stops replaying, with
     * no compile error anywhere.
     *
     * <p>Spelled out, a rename breaks this map instead, which is a compile error. Cheap
     * insurance against silently losing the ability to read your own history.
     */
    private static final Map<String, Class<? extends AccountEvent>> TYPES_BY_NAME = Map.of(
            "AccountOpened", AccountEvent.AccountOpened.class,
            "MoneyDeposited", AccountEvent.MoneyDeposited.class,
            "MoneyWithdrawn", AccountEvent.MoneyWithdrawn.class,
            "TransferInitiated", AccountEvent.TransferInitiated.class,
            "MoneyDebited", AccountEvent.MoneyDebited.class,
            "MoneyCredited", AccountEvent.MoneyCredited.class,
            "TransferCompleted", AccountEvent.TransferCompleted.class,
            "TransferCompensated", AccountEvent.TransferCompensated.class);

    private static final Map<Class<? extends AccountEvent>, String> NAMES_BY_TYPE =
            TYPES_BY_NAME.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    private final ObjectMapper objectMapper;

    public AccountEventJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String typeName(AccountEvent event) {
        String name = NAMES_BY_TYPE.get(event.getClass());
        if (name == null) {
            throw new IllegalStateException(
                    "event type is not registered in AccountEventJsonCodec: " + event.getClass().getName());
        }
        return name;
    }

    public String toJson(AccountEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise event: " + event, e);
        }
    }

    /**
     * @throws IllegalStateException if the stored type name has no registered class —
     *     history that cannot be read is not recoverable by guessing, so this fails hard
     *     rather than skipping the event and silently computing a wrong balance.
     */
    public AccountEvent fromJson(String eventType, String payloadJson) {
        Class<? extends AccountEvent> type = TYPES_BY_NAME.get(eventType);
        if (type == null) {
            throw new IllegalStateException("unknown event type in the event store: " + eventType);
        }
        try {
            return objectMapper.readValue(payloadJson, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not deserialise stored %s: %s".formatted(eventType, payloadJson), e);
        }
    }
}
