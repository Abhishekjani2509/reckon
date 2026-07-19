-- The account_transactions read model: transaction history, one row per event.
--
-- A second projection built from the same event stream, serving "show me this account's
-- transactions". Like account_balances it is derived and disposable, rebuildable from the
-- log, and idempotent under at-least-once delivery.

CREATE TABLE account_transactions (
    account_id      TEXT        NOT NULL,

    -- Per-aggregate version. Paired with account_id it is the primary key, which does
    -- double duty: it de-duplicates re-delivered events (INSERT ... ON CONFLICT DO
    -- NOTHING) and it is the pagination cursor -- monotonic per account, so "older than
    -- version N" is a clean, stable page boundary with no OFFSET scan.
    version         BIGINT      NOT NULL,

    event_type      TEXT        NOT NULL,

    -- Signed: positive for a credit, negative for a debit, zero for the opening entry.
    -- One signed column reads as a ledger and sums to the balance.
    amount_minor    BIGINT      NOT NULL,

    -- The account balance immediately after this transaction. Captured at projection
    -- time so history shows a running balance without re-folding the stream.
    balance_after   BIGINT      NOT NULL,

    currency        TEXT        NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    idempotency_key TEXT,

    PRIMARY KEY (account_id, version)
);

-- Serves the history query: newest first within an account, resumable from a cursor.
--   WHERE account_id = ? [AND version < ?cursor] ORDER BY version DESC LIMIT ?
CREATE INDEX account_transactions_cursor_idx ON account_transactions (account_id, version DESC);
