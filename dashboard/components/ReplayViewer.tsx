"use client";

import { useEffect, useRef, useState } from "react";
import { getEvents, type EventRecord } from "../app/lib/api";
import { balanceAfter, eventDelta, formatMinor } from "../app/lib/money";

// Rebuilds an account's balance from its raw event stream, one event at a time — the same
// fold the aggregate does on the server, run in the browser. The point it makes visible:
// the balance is not stored, it is computed from the log.
export default function ReplayViewer({
  accountId,
  currency,
  reportedBalance,
  refreshKey,
}: {
  accountId: string;
  currency: string;
  reportedBalance: number | null;
  refreshKey: number;
}) {
  const [events, setEvents] = useState<EventRecord[]>([]);
  const [step, setStep] = useState(-1); // -1 = before any event (balance 0)
  const [playing, setPlaying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    let live = true;
    getEvents(accountId)
      .then((e) => {
        if (!live) return;
        setEvents(e);
        setStep(e.length - 1); // start fully replayed
        setError(null);
      })
      .catch((err) => live && setError(String(err)));
    return () => {
      live = false;
    };
  }, [accountId, refreshKey]);

  useEffect(() => {
    if (!playing) return;
    timer.current = setInterval(() => {
      setStep((s) => {
        if (s >= events.length - 1) {
          setPlaying(false);
          return s;
        }
        return s + 1;
      });
    }, 550);
    return () => {
      if (timer.current) clearInterval(timer.current);
    };
  }, [playing, events.length]);

  const reconstructed = balanceAfter(events, step);
  const fullyReplayed = step === events.length - 1;
  const matches = reportedBalance != null && fullyReplayed && reconstructed === reportedBalance;

  function replayFromStart() {
    setStep(-1);
    setPlaying(true);
  }

  if (error) return <p className="error">Could not load events: {error}</p>;
  if (events.length === 0) return <p className="hint">No events yet.</p>;

  return (
    <div>
      <div className="replay-controls">
        <button className="ghost" onClick={() => setStep((s) => Math.max(-1, s - 1))} disabled={step < 0}>
          ‹ step
        </button>
        <button className="ghost" onClick={() => setStep((s) => Math.min(events.length - 1, s + 1))} disabled={fullyReplayed}>
          step ›
        </button>
        <button onClick={replayFromStart} disabled={playing}>
          {playing ? "replaying…" : "▶ replay from zero"}
        </button>
        <span className="hint">
          event {step + 1} of {events.length}
        </span>
      </div>

      <div className="row" style={{ justifyContent: "space-between", alignItems: "baseline" }}>
        <div>
          <div className="muted">reconstructed balance</div>
          <div className="replay-balance">{formatMinor(reconstructed, currency)}</div>
        </div>
        {reportedBalance != null && (
          <div style={{ textAlign: "right" }}>
            <div className="muted">reported by query service</div>
            <div className="mono">{formatMinor(reportedBalance, currency)}</div>
            {fullyReplayed && (
              <div className={`match ${matches ? "ok" : "no"}`}>
                {matches ? "✓ reconstruction matches" : "✗ mismatch"}
              </div>
            )}
          </div>
        )}
      </div>

      <div style={{ marginTop: 12 }}>
        {events.map((e, i) => {
          const delta = eventDelta(e);
          const cls = i === step ? "current" : i > step ? "future" : "";
          return (
            <div key={e.eventId} className={`event ${cls}`}>
              <span className="v mono">v{e.version}</span>
              <span className="mono">{e.eventType}</span>
              <span className={`delta ${delta > 0 ? "pos" : delta < 0 ? "neg" : "muted"}`}>
                {delta === 0 ? "—" : (delta > 0 ? "+" : "") + formatMinor(delta, currency)}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
