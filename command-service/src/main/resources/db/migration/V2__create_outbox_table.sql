-- The transactional outbox.
--
-- Publishing an event to Kafka and appending it to the event store are two writes to two
-- systems that share no transaction. Done naively they can disagree: commit-then-crash
-- loses the publish, publish-then-rollback invents an event. There is no ordering of the
-- two that is safe.
--
-- This table removes the second system from the write path. A command inserts one row
-- here in the SAME transaction that appends the event -- one database, one transaction,
-- atomic. A separate poller later reads unpublished rows, sends them to Kafka, and marks
-- them published. The command never touches Kafka, so the command's atomicity is intact.

CREATE TABLE outbox (
    -- Insertion order. The poller drains in this order, which preserves per-aggregate
    -- order because optimistic concurrency serialises writes to any single aggregate --
    -- two appends to the same account cannot both commit, so their outbox ids cannot
    -- interleave.
    id           BIGSERIAL   PRIMARY KEY,

    -- The event this row will publish. UNIQUE so a bug that tried to enqueue an event
    -- twice fails loudly at insert rather than duplicating a message on the topic.
    event_id     UUID        NOT NULL UNIQUE,

    -- The Kafka message key. Same key -> same partition -> ordered delivery per account,
    -- while different accounts spread across partitions and flow in parallel.
    aggregate_id TEXT        NOT NULL,

    topic        TEXT        NOT NULL,

    -- The exact bytes to put on the topic: the full event envelope. Stored complete so
    -- the poller needs no knowledge of accounts -- it reads (key, topic, payload) and
    -- sends.
    payload      JSONB       NOT NULL,

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- NULL until published. This nullable column is the entire delivery state machine:
    -- unpublished rows are the poller's work queue, and a crash mid-publish simply leaves
    -- the row NULL, so it is retried. That retry is why delivery is at-least-once and why
    -- consumers must be idempotent.
    published_at TIMESTAMPTZ
);

-- The poller's query is "oldest unpublished rows first". A partial index over only the
-- unpublished rows keeps that scan cheap even once the table holds millions of already
-- published rows -- published rows are not in the index at all.
CREATE INDEX outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;
