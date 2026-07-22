-- Aggregate snapshots: bound the cost of loading an aggregate.
--
-- Without this, loading an account replays every event from version 1, so a long-lived
-- account gets slower to load on every command. A snapshot is the aggregate's folded state
-- at a known version; loading starts from the latest snapshot and replays only the events
-- after it.
--
-- Snapshots are derived and disposable, exactly like a read model. This table can be
-- dropped and every row rebuilt by replaying the event log — it is never a source of truth,
-- only a cache of a fold.

CREATE TABLE snapshots (
    -- One row per aggregate: the latest snapshot. Loading only ever needs the newest, so
    -- older snapshots are overwritten rather than kept.
    aggregate_id TEXT        PRIMARY KEY,

    -- The version this snapshot reflects. Loading replays events with version > this.
    version      BIGINT      NOT NULL,

    -- The folded aggregate state (balance, currency, owner, opened) as JSON.
    state        JSONB       NOT NULL,

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
