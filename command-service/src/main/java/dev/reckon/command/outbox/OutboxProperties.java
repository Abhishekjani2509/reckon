package dev.reckon.command.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox and topic settings, bound from the {@code reckon.outbox.*} config.
 *
 * @param topic the Kafka topic account events are published to
 * @param partitions partition count for that topic; keyed by aggregate id, so this is
 *     the ceiling on how many accounts can be processed in parallel downstream
 * @param pollDelayMs delay between poll passes — the main lever on write-to-publish latency
 * @param batchSize rows drained per pass, bounding how long a single pass holds row locks
 */
@ConfigurationProperties(prefix = "reckon.outbox")
public record OutboxProperties(
        String topic,
        int partitions,
        long pollDelayMs,
        int batchSize
) {}
