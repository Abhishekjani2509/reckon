-- The account_balances read model.
--
-- A denormalised view built by consuming events: one row per account, current balance
-- ready to read without replaying a stream. This table is derived and disposable -- it
-- can be dropped and rebuilt entirely from the event log, and it is never the source of
-- truth for anything.

CREATE TABLE account_balances (
    account_id    TEXT        PRIMARY KEY,
    owner         TEXT        NOT NULL,
    currency      TEXT        NOT NULL,
    balance_minor BIGINT      NOT NULL,

    -- The idempotency guard, and the reason this projection survives at-least-once
    -- delivery. It records the version of the last event folded into this row. The
    -- projector applies an event only when its version is exactly last_version + 1, in a
    -- single guarded UPDATE:
    --
    --   UPDATE ... SET balance_minor = balance_minor + delta, last_version = :v
    --   WHERE account_id = :id AND last_version = :v - 1
    --
    -- A re-delivered event whose version was already applied matches zero rows and
    -- changes nothing (idempotent); an out-of-order event matches zero rows and waits.
    -- Ordering and de-duplication fall out of one WHERE clause, atomically, with no
    -- read-modify-write race.
    last_version  BIGINT      NOT NULL,

    -- When this row last changed. The gap between an event's occurredAt and this is the
    -- projection lag -- the core eventual-consistency signal, measured properly on Day 10.
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
