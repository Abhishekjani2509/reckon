package dev.reckon.command.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires up the outbox: enables its config properties, schedules the poller, and declares
 * the account-events topic.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {

    /**
     * Declares the topic so it exists with a known partition count rather than being
     * auto-created with broker defaults. KafkaAdmin creates it on startup and is a no-op
     * if it already exists, so this is safe to run every boot.
     *
     * <p>One replica: single-broker Redpanda in local dev cannot replicate further, and
     * asking for more would leave the topic under-replicated. Replication is a
     * deployment-topology concern, set per environment.
     */
    @Bean
    NewTopic accountEventsTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(properties.partitions())
                .replicas(1)
                .build();
    }
}
