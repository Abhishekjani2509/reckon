"use client";

import { useCallback, useEffect, useState } from "react";
import { listAccounts, openAccount, type AccountSummary } from "./lib/api";
import { formatMinor } from "./lib/money";
import AccountDetail from "../components/AccountDetail";

export default function Dashboard() {
  const [accounts, setAccounts] = useState<AccountSummary[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [owner, setOwner] = useState("");
  const [currency, setCurrency] = useState("USD");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const list = await listAccounts();
      setAccounts(list);
      setError(null);
      setSelected((cur) => cur ?? list[0]?.accountId ?? null);
    } catch (err) {
      setError(String(err));
    }
  }, []);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 3000); // live balances
    return () => clearInterval(t);
  }, [refresh]);

  async function create() {
    if (!owner.trim()) return;
    setBusy(true);
    try {
      const acc = await openAccount(owner.trim(), currency);
      setOwner("");
      await refresh();
      setSelected(acc.accountId);
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  }

  const current = accounts.find((a) => a.accountId === selected) ?? null;

  return (
    <div className="wrap">
      <header className="top">
        <h1>Reckon</h1>
        <span className="sub">event-sourced ledger — balances, history, and replay</span>
      </header>

      <div className="grid" style={{ marginTop: 16 }}>
        <div className="panel">
          <h2>Accounts</h2>
          <div className="row" style={{ marginBottom: 12 }}>
            <input placeholder="owner" value={owner} onChange={(e) => setOwner(e.target.value)} style={{ flex: 1 }} />
            <select value={currency} onChange={(e) => setCurrency(e.target.value)}>
              <option>USD</option>
              <option>EUR</option>
              <option>GBP</option>
            </select>
            <button disabled={busy} onClick={create}>
              Open
            </button>
          </div>
          {error && <p className="error">{error}</p>}
          {accounts.length === 0 && !error && <p className="hint">No accounts yet — open one.</p>}
          {accounts.map((a) => (
            <div
              key={a.accountId}
              className={`acct ${a.accountId === selected ? "sel" : ""}`}
              onClick={() => setSelected(a.accountId)}
            >
              <div>
                <div>{a.owner}</div>
                <div className="hint mono">{a.accountId.slice(0, 16)}…</div>
              </div>
              <div className="bal">{formatMinor(a.balanceMinor, a.currency)}</div>
            </div>
          ))}
        </div>

        <div className="panel">
          {current ? (
            <AccountDetail account={current} accounts={accounts} onChanged={refresh} />
          ) : (
            <p className="hint">Select or open an account.</p>
          )}
        </div>
      </div>
    </div>
  );
}
