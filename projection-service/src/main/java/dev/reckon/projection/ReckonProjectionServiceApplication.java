package dev.reckon.projection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The read side of Reckon: consumes account events from Kafka and maintains read models
 * shaped for querying.
 *
 * <p>It is the mirror image of command-service. command-service owns the event store and
 * only writes; this service owns the read database and only projects. Neither reaches
 * into the other's storage — the event envelope on the Kafka topic is the entire contract
 * between them, which is the CQRS boundary made concrete.
 */
@SpringBootApplication
public class ReckonProjectionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReckonProjectionServiceApplication.class, args);
    }
}
