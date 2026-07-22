"use client";

import { useCallback, useEffect, useState } from "react";
import {
  deposit,
  getBalance,
  transfer,
  withdraw,
  type AccountSummary,
  type BalanceResponse,
} from "../app/lib/api";
import { formatMinor } from "../app/lib/money";
import TransactionHistory from "./TransactionHistory";
import ReplayViewer from "./ReplayViewer";

// Detail for one account: live balance (with cache source), actions, history, and replay.
// Every action bumps refreshKey, which re-reads the balance and re-fetches the children —
// read-your-writes: the event appears at once, the projected balance follows a beat later.
export default function AccountDetail({
  account,
  accounts,
  onChanged,
}: {
  account: AccountSummary;
  accounts: AccountSummary[];
  onChanged: () => void;
}) {
  const [balance, setBalance] = useState<BalanceResponse | null>(null);
  const [source, setSource] = useState<string>("");
  const [refreshKey, setRefreshKey] = useState(0);
  const [amount, setAmount] = useState("10.00");
  const [dest, setDest] = useState("");
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const id = account.accountId;
  const currency = account.currency;

  const refresh = useCallback(async () => {
    const b = await getBalance(id);
    if (b) {
      setBalance(b.balance);
      setSource(b.source);
    }
  }, [id]);

  useEffect(() => {
    setRefreshKey(0);
    setNote(null);
    setError(null);
    refresh();
    // re-read the projected balance briefly after mount to catch eventual-consistency lag
    const t = setTimeout(refresh, 700);
    return () => clearTimeout(t);
  }, [id, refresh]);

  function minor(): number {
    return Math.round(parseFloat(amount) * 100);
  }

  async function afterCommand() {
    setRefreshKey((k) => k + 1);
    onChanged();
    await refresh();
    setTimeout(refresh, 700); // projection catches up
  }

  async function run(action: () => Promise<unknown>, label: string) {
    setBusy(true);
    setError(null);
    setNote(null);
    try {
      await action();
      setNote(label);
      await afterCommand();
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  }

  async function doTransfer() {
    if (!dest) {
      setError("pick a destination account");
      return;
    }
    setBusy(true);
    setError(null);
    setNote(null);
    try {
      const res = await transfer(id, dest, minor(), currency);
      setNote(
        res.body.status === "COMPENSATED"
          ? `transfer compensated: ${res.body.failureReason}`
          : `transfer ${res.body.status?.toLowerCase()}`,
      );
      await afterCommand();
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <div className="row" style={{ justifyContent: "space-between", alignItems: "baseline" }}>
        <div>
          <div className="muted">{account.owner}</div>
          <div className="balance-big">{balance ? formatMinor(balance.balanceMinor, currency) : "—"}</div>
          <div className="hint mono">{id}</div>
        </div>
        <div style={{ textAlign: "right" }}>
          {source && <span className={`pill ${source}`}>balance from {source}</span>}
          <div className="hint" style={{ marginTop: 6 }}>
            version {balance?.version ?? account.version}
          </div>
        </div>
      </div>

      <div style={{ marginTop: 14 }}>
        <div className="row">
          <input
            style={{ width: 110 }}
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            inputMode="decimal"
            aria-label="amount"
          />
          <button disabled={busy} onClick={() => run(() => deposit(id, minor(), currency), "deposited")}>
            Deposit
          </button>
          <button className="ghost" disabled={busy} onClick={() => run(() => withdraw(id, minor(), currency), "withdrew")}>
            Withdraw
          </button>
        </div>
        <div className="row">
          <select value={dest} onChange={(e) => setDest(e.target.value)} aria-label="destination">
            <option value="">transfer to…</option>
            {accounts
              .filter((a) => a.accountId !== id)
              .map((a) => (
                <option key={a.accountId} value={a.accountId}>
                  {a.owner} ({a.accountId.slice(0, 12)}…)
                </option>
              ))}
          </select>
          <button className="ghost" disabled={busy} onClick={doTransfer}>
            Transfer
          </button>
        </div>
        {note && <p className="hint">✓ {note}</p>}
        {error && <p className="error">{error}</p>}
      </div>

      <h2 style={{ marginTop: 22 }}>Replay from the event log</h2>
      <p className="hint" style={{ marginTop: -6 }}>
        The same fold the aggregate does, in your browser. Balance is computed from the log, not stored.
      </p>
      <ReplayViewer
        accountId={id}
        currency={currency}
        reportedBalance={balance?.balanceMinor ?? null}
        refreshKey={refreshKey}
      />

      <h2 style={{ marginTop: 22 }}>Transaction history</h2>
      <TransactionHistory accountId={id} currency={currency} refreshKey={refreshKey} />
    </div>
  );
}
