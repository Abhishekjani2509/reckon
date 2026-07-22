// Typed access to the Reckon backends, proxied through Next (see next.config.ts):
//   /api/command → command-service (write + raw event stream)
//   /api/query   → query-service   (balances + history)

const CMD = "/api/command";
const QRY = "/api/query";

export type AccountSummary = {
  accountId: string;
  owner: string;
  currency: string;
  balanceMinor: number;
  version: number;
};

export type BalanceResponse = {
  accountId: string;
  balanceMinor: number;
  currency: string;
  version: number;
};

export type Transaction = {
  version: number;
  eventType: string;
  amountMinor: number;
  balanceAfter: number;
  currency: string;
  occurredAt: string;
};

export type TransactionPage = {
  transactions: Transaction[];
  nextCursor: number | null;
};

// The raw event, straight from the log. payload shape varies by eventType.
export type EventRecord = {
  eventId: string;
  version: number;
  eventType: string;
  payload: Record<string, unknown> & { amountMinor?: number; currency?: string; owner?: string };
  occurredAt: string;
};

function newKey(): string {
  return crypto.randomUUID();
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`${res.status} ${res.statusText}: ${body}`);
  }
  return res.json() as Promise<T>;
}

// --- reads ---

export function listAccounts(): Promise<AccountSummary[]> {
  return fetch(`${QRY}/accounts`, { cache: "no-store" }).then((r) => json<AccountSummary[]>(r));
}

export async function getBalance(id: string): Promise<{ balance: BalanceResponse; source: string } | null> {
  const res = await fetch(`${QRY}/accounts/${id}/balance`, { cache: "no-store" });
  if (res.status === 404) return null;
  const balance = await json<BalanceResponse>(res);
  return { balance, source: res.headers.get("X-Balance-Source") ?? "unknown" };
}

export function getTransactions(id: string, cursor?: number, limit = 50): Promise<TransactionPage> {
  const q = new URLSearchParams({ limit: String(limit) });
  if (cursor != null) q.set("cursor", String(cursor));
  return fetch(`${QRY}/accounts/${id}/transactions?${q}`, { cache: "no-store" }).then((r) =>
    json<TransactionPage>(r),
  );
}

export function getEvents(id: string): Promise<EventRecord[]> {
  return fetch(`${CMD}/accounts/${id}/events`, { cache: "no-store" }).then((r) => json<EventRecord[]>(r));
}

// --- commands ---

export function openAccount(owner: string, currency: string): Promise<AccountSummary> {
  return fetch(`${CMD}/accounts`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ owner, currency, idempotencyKey: newKey() }),
  }).then((r) => json<AccountSummary>(r));
}

function amountCommand(id: string, path: string, amountMinor: number, currency: string) {
  return fetch(`${CMD}/accounts/${id}/${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ amountMinor, currency, idempotencyKey: newKey() }),
  });
}

export async function deposit(id: string, amountMinor: number, currency: string) {
  return json(await amountCommand(id, "deposits", amountMinor, currency));
}

export async function withdraw(id: string, amountMinor: number, currency: string) {
  return json(await amountCommand(id, "withdrawals", amountMinor, currency));
}

export async function transfer(
  sourceId: string,
  destinationAccountId: string,
  amountMinor: number,
  currency: string,
) {
  const res = await fetch(`${CMD}/accounts/${sourceId}/transfers`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ destinationAccountId, amountMinor, currency, idempotencyKey: newKey() }),
  });
  // A compensated transfer comes back 422 with a body; surface it rather than throwing.
  const body = await res.json();
  return { ok: res.ok, status: res.status, body };
}
