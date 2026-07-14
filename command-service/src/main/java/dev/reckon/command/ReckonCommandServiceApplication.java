package dev.reckon.command;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The write side of Reckon: validates commands, replays aggregates from the event
 * store, and appends new events under optimistic concurrency.
 *
 * <p>This service owns the source of truth and nothing else. It never serves a balance
 * query — that is the query service's job, reading a projection. Keeping the two apart
 * is the C and Q in CQRS, and it is what lets each side be modelled and scaled for the
 * job it actually does.
 */
@SpringBootApplication
public class ReckonCommandServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReckonCommandServiceApplication.class, args);
    }
}
