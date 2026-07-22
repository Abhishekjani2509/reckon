"use client";

import { useEffect, useState } from "react";
import { getTransactions, type Transaction } from "../app/lib/api";
import { formatMinor } from "../app/lib/money";

// Transaction history from the query-service read model, newest first, cursor-paginated.
export default function TransactionHistory({
  accountId,
  currency,
  refreshKey,
}: {
  accountId: string;
  currency: string;
  refreshKey: number;
}) {
  const [rows, setRows] = useState<Transaction[]>([]);
  const [cursor, setCursor] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    setLoading(true);
    getTransactions(accountId, undefined, 10)
      .then((page) => {
        if (!live) return;
        setRows(page.transactions);
        setCursor(page.nextCursor);
        setError(null);
      })
      .catch((err) => live && setError(String(err)))
      .finally(() => live && setLoading(false));
    return () => {
      live = false;
    };
  }, [accountId, refreshKey]);

  async function loadMore() {
    if (cursor == null) return;
    setLoading(true);
    try {
      const page = await getTransactions(accountId, cursor, 10);
      setRows((r) => [...r, ...page.transactions]);
      setCursor(page.nextCursor);
    } catch (err) {
      setError(String(err));
    } finally {
      setLoading(false);
    }
  }

  if (error) return <p className="error">{error}</p>;
  if (rows.length === 0) return <p className="hint">{loading ? "Loading…" : "No transactions yet."}</p>;

  return (
    <div>
      <table>
        <thead>
          <tr>
            <th>v</th>
            <th>type</th>
            <th className="num">amount</th>
            <th className="num">balance after</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((t) => (
            <tr key={t.version}>
              <td className="muted mono">v{t.version}</td>
              <td className="mono">{t.eventType}</td>
              <td className={`num ${t.amountMinor > 0 ? "pos" : t.amountMinor < 0 ? "neg" : "muted"}`}>
                {t.amountMinor === 0 ? "—" : (t.amountMinor > 0 ? "+" : "") + formatMinor(t.amountMinor, currency)}
              </td>
              <td className="num mono">{formatMinor(t.balanceAfter, currency)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {cursor != null && (
        <div style={{ marginTop: 10 }}>
          <button className="ghost" onClick={loadMore} disabled={loading}>
            {loading ? "…" : "Load older"}
          </button>
        </div>
      )}
    </div>
  );
}
