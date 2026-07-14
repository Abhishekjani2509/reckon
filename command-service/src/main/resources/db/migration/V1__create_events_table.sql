-- The event store. This is the source of truth for all of Reckon.
--
-- Everything else in the system -- balances, transaction history, the Redis cache,
-- the dashboard -- is a derived view of this table and can be dropped and rebuilt
-- from it. This table itself can never be rebuilt from anything. It is the record.

CREATE TABLE events (
    -- Global ordering. A projector that has consumed through sequence N knows exactly
    -- where to resume, and a full rebuild replays in this order. BIGSERIAL rather than
    -- a timestamp because wall clocks jump backwards and ties within the same
    -- millisecond are unorderable.
    sequence_number BIGSERIAL PRIMARY KEY,

    event_id        UUID        NOT NULL UNIQUE,

    -- Which aggregate this event belongs to. Also the broker partition key, which is
    -- what allows projectors to scale out while preserving per-account ordering.
    aggregate_id    TEXT        NOT NULL,
    aggregate_type  TEXT        NOT NULL,

    -- Per-aggregate version, starting at 1 and incrementing by exactly one. Scoped per
    -- aggregate, not global: account A and account B both have a version 1.
    version         BIGINT      NOT NULL CHECK (version > 0),

    event_type      TEXT        NOT NULL,

    -- The event body. JSONB rather than a column per field because each event type
    -- carries a different shape -- MoneyDeposited has an amount, AccountOpened has a
    -- currency and an owner. Columns would mean a migration per new event type; the
    -- envelope above stays structured and indexed, the body stays flexible.
    payload         JSONB       NOT NULL,

    -- When the fact happened, as asserted by the service that recorded it. Distinct
    -- from sequence_number, which is when the store accepted it.
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- THE constraint. This one line is Reckon's concurrency control.
    --
    -- To append, a command must claim the aggregate's next version. Two concurrent
    -- withdrawals both replay account acc_1 to version 7, both compute a valid balance,
    -- and both try to INSERT version 8. Postgres accepts exactly one; the other fails
    -- on this constraint, and the command retries against the now-current state -- where
    -- it may correctly be rejected for insufficient funds.
    --
    -- That is optimistic concurrency: no locks, no SELECT FOR UPDATE, no lost updates.
    -- Without this constraint both writes land and the account has spent money twice.
    CONSTRAINT events_aggregate_version_unique UNIQUE (aggregate_id, version)
);

-- Rehydration is the hot path on the write side: "give me every event for this
-- aggregate, in version order, so I can fold it into current state." Snapshots add a
-- lower bound to that version range, which this index also serves.
CREATE INDEX events_aggregate_id_version_idx ON events (aggregate_id, version);

-- Append-only, enforced rather than merely intended.
--
-- A comment asking people not to UPDATE the source of truth is a suggestion. An audit
-- trail that can be quietly edited is not an audit trail, and the guarantee that read
-- models rebuild identically holds only if history cannot be rewritten. So the database
-- refuses. This deliberately makes bad data un-fixable in place: the event-sourced
-- answer to a wrong event is a new compensating event, never a correction of the
-- original.
CREATE OR REPLACE FUNCTION events_reject_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'events is append-only: % rejected on the event store', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER events_append_only
    BEFORE UPDATE OR DELETE ON events
    FOR EACH ROW EXECUTE FUNCTION events_reject_mutation();
