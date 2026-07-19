package dev.reckon.query.balance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads hot balances from Redis — the fast path for the balance endpoint.
 *
 * <p>Read-only. query-service never writes these keys; projection-service is their sole
 * writer. On a miss the caller falls back to Postgres rather than populating Redis here,
 * which keeps the single-writer rule intact and avoids the invalidation races that
 * populate-on-read caching creates.
 */
@Component
public class HotBalanceReader {

    private static final String KEY_PREFIX = "balance:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public HotBalanceReader(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public Optional<BalanceResponse> find(String accountId) {
        String json = redis.opsForValue().get(KEY_PREFIX + accountId);
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(deserialize(json));
    }

    private BalanceResponse deserialize(String json) {
        try {
            // The Redis value shape is the contract projection-service writes: the same
            // fields, agreed on as JSON rather than as shared code.
            HotBalance value = objectMapper.readValue(json, HotBalance.class);
            return new BalanceResponse(value.accountId(), value.balanceMinor(), value.currency(), value.version());
        } catch (Exception e) {
            throw new IllegalStateException("could not deserialise hot balance: " + json, e);
        }
    }

    private record HotBalance(String accountId, long balanceMinor, String currency, long version) {}
}
