package dev.reckon.projection.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.reckon.projection.projection.BalanceSnapshot;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes hot balances to Redis — the second read model, kept in step with Postgres by the
 * projector.
 *
 * <p>The projector is the <b>only</b> writer to these keys. The query service reads them
 * and never writes, so there is exactly one source of truth for the cache and none of the
 * invalidation races that "populate-on-read" caching invites. Redis here is a projection,
 * not a cache bolted onto the read path.
 *
 * <p>The stored value is the full {@link HotBalance} JSON so a reader gets balance,
 * currency, and version in one round trip.
 */
@Component
public class HotBalanceStore {

    // A namespaced key per account. Flat key, no TTL: this is a maintained projection, not
    // an expiring cache -- it should live exactly as long as the account does.
    private static final String KEY_PREFIX = "balance:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public HotBalanceStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void put(BalanceSnapshot snapshot) {
        HotBalance value = new HotBalance(
                snapshot.accountId(), snapshot.balanceMinor(), snapshot.currency(), snapshot.version());
        redis.opsForValue().set(KEY_PREFIX + snapshot.accountId(), serialize(value));
    }

    private String serialize(HotBalance value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise hot balance for " + value.accountId(), e);
        }
    }

    /**
     * The Redis value shape. Not shared code with the query service — the two agree on
     * this JSON as a contract, the same way they agree on the Kafka envelope.
     */
    public record HotBalance(String accountId, long balanceMinor, String currency, long version) {}
}
