package dev.reckon.projection.consumer;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the account-events topic from the read side as well.
 *
 * <p>Both the producer (command-service) and this consumer declare the topic with the same
 * 3 partitions. KafkaAdmin creates it if missing and is a no-op if it exists, so whichever
 * service boots first establishes the correct topic — and neither side depends on the
 * broker auto-creating a wrong single-partition one. Belt and braces against the startup
 * race, and standard practice: a consumer that requires a topic should assert its shape.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    NewTopic accountEventsTopic(
            @Value("${reckon.projection.topic}") String topic,
            @Value("${reckon.projection.partitions}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }
}
