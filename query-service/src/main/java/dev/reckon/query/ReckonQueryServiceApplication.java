package dev.reckon.query;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The query side of Reckon: serves balances and transaction history from the read models.
 *
 * <p>Read-only by construction. It consumes no events, owns no schema, and writes to
 * neither Postgres nor Redis — it only reads what projection-service has already built.
 * Balances are served hot from Redis with Postgres as the durable fallback; history comes
 * from the transactions read model with cursor pagination.
 */
@SpringBootApplication
public class ReckonQueryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReckonQueryServiceApplication.class, args);
    }
}
