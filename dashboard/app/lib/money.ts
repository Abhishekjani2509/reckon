import type { EventRecord } from "./api";

/** Minor units (cents) to a display string. 12345 -> "123.45". */
export function formatMinor(amountMinor: number, currency = "USD"): string {
  const sign = amountMinor < 0 ? "-" : "";
  const abs = Math.abs(amountMinor);
  return `${sign}${(abs / 100).toFixed(2)} ${currency}`;
}

/**
 * The per-event balance delta — the SAME fold the aggregate applies on the server.
 * Money moves on deposits/credits (+) and withdrawals/debits (-); the account-open and
 * transfer markers move nothing.
 */
export function eventDelta(event: EventRecord): number {
  const amount = typeof event.payload.amountMinor === "number" ? event.payload.amountMinor : 0;
  switch (event.eventType) {
    case "MoneyDeposited":
    case "MoneyCredited":
    case "TransferCompensated":
      return amount;
    case "MoneyWithdrawn":
    case "MoneyDebited":
      return -amount;
    default:
      // AccountOpened, TransferInitiated, TransferCompleted: balance-neutral.
      return 0;
  }
}

/** Balance after folding events[0..upTo] inclusive. This is balance-as-a-fold, in the browser. */
export function balanceAfter(events: EventRecord[], upTo: number): number {
  let balance = 0;
  for (let i = 0; i <= upTo && i < events.length; i++) {
    balance += eventDelta(events[i]);
  }
  return balance;
}
