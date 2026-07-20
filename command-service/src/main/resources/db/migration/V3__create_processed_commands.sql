-- Command de-duplication for idempotency.
--
-- Delivery is at-least-once and clients retry, so the same command can arrive more than
-- once. This table records, per aggregate, which idempotency keys have already been
-- applied and the result they produced -- so a retry returns the original result instead
-- of applying the command a second time.
--
-- The row is written in the SAME transaction as the command's events (see JdbcEventStore),
-- so a command can never be recorded as done without its events, nor its events written
-- without being recorded. The two are one atomic fact.

CREATE TABLE processed_commands (
    aggregate_id    TEXT        NOT NULL,

    -- Client-supplied, unique per aggregate. This is the whole idempotency contract: the
    -- same key for the same intended operation, resent on a retry.
    idempotency_key TEXT        NOT NULL,

    -- The result the command produced, as JSON. Stored so a retry can replay the exact
    -- original response without re-touching the aggregate. For a transfer this begins as a
    -- PENDING marker and is finalised when the saga reaches a terminal step.
    result_json     JSONB       NOT NULL,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The primary key IS the dedup guarantee. Two concurrent identical commands both try
    -- to insert this row; exactly one wins, the other gets a unique violation and returns
    -- the stored result. Correctness rests on this constraint, not on the pre-check.
    PRIMARY KEY (aggregate_id, idempotency_key)
);
