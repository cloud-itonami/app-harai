/**
 * harai kotoba — registry.
 *
 * Plaintext path (settlementRail): sdk.write / sdk.read — public clearing-rail
 * reference catalog.
 * E2E path (payment / transaction / balance): sdk.encryptedWrite /
 * sdk.encryptedRead — PII + ledger-entry bodies sealed in the kotoba envelope
 * (ADR-2605181100), read-cap = owner DID. The substrate never sees payer/payee
 * DIDs or amounts in plaintext.
 *
 * The fiat merchant-of-record settlement / clearing EXECUTION stays etzhayyim and is
 * invoked under consent-capability; here we only front the ledger DATA. Money is
 * carried as a decimal string plus an exact integer minor-unit field, and every
 * rollup sums the integer minor units — no float arithmetic anywhere.
 */

import type { Etzhayyim } from "@etzhayyim/sdk";
import {
  BALANCE_INNER_TYPE,
  PAYMENT_INNER_TYPE,
  RAIL_COLLECTION,
  TRANSACTION_INNER_TYPE,
  balanceRkey,
  isDecimalString,
  isPaymentStatus,
  isTxDirection,
  isUint,
  paymentRkey,
  railDidFor,
  railRkey,
  transactionRkey,
  type BalanceBody,
  type BalanceView,
  type CoverageInput,
  type CoverageOutput,
  type GetBalanceInput,
  type GetBalanceOutput,
  type GetPaymentInput,
  type GetPaymentOutput,
  type GetRailInput,
  type GetRailOutput,
  type ListPaymentsInput,
  type ListPaymentsOutput,
  type ListRailsInput,
  type ListRailsOutput,
  type ListTransactionsInput,
  type ListTransactionsOutput,
  type PaymentBody,
  type PaymentView,
  type RecordPaymentInput,
  type RecordPaymentOutput,
  type RecordTransactionInput,
  type RecordTransactionOutput,
  type RegisterRailInput,
  type RegisterRailOutput,
  type SetBalanceInput,
  type SetBalanceOutput,
  type SettlementRailRecord,
  type SettlementRailView,
  type TransactionBody,
  type TransactionView,
} from "./types.js";

const PAGE_LIMIT = 100;
const DEFAULT_MAX_SCAN = 10_000;

// ─── Settlement-rail catalog (PLAINTEXT) ────────────────────────────

export async function registerRail(e: Etzhayyim, input: RegisterRailInput): Promise<RegisterRailOutput> {
  if (!input.railId || !input.currency || !input.railLabel) return { status: "rejected", error: "missingRequiredFields" };
  if (!isUint(input.minorUnitExp)) return { status: "rejected", error: "invalidMinorUnitExp" };
  const rkey = railRkey(input.railId);
  const existing = await e.read<SettlementRailRecord>({ collection: RAIL_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", railUri: existing.records[0].uri, did: existing.records[0].value.did, railId: input.railId };
  }
  const now = new Date().toISOString();
  const did = railDidFor(input.railId);
  const record: SettlementRailRecord = {
    did,
    railId: input.railId,
    currency: input.currency,
    railLabel: input.railLabel,
    enabled: input.enabled ?? true,
    minorUnitExp: input.minorUnitExp,
    createdAt: now,
  };
  const receipt = await e.write({ collection: RAIL_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "registered", railUri: receipt.uri, did, railId: input.railId };
}

export async function getRail(e: Etzhayyim, input: GetRailInput): Promise<GetRailOutput> {
  if (!input.railId) return { error: "invalidRailId" };
  const rkey = railRkey(input.railId);
  const resp = await e.read<SettlementRailRecord>({ collection: RAIL_COLLECTION, rkey }).catch(() => ({ records: [] }));
  const r = resp.records[0];
  if (!r?.value) return { error: "notFound" };
  return { rail: { ...r.value, railUri: r.uri } };
}

export async function listRails(e: Etzhayyim, input: ListRailsInput = {}): Promise<ListRailsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<SettlementRailRecord>({ collection: RAIL_COLLECTION, cursor: input.cursor, limit });
  const items: SettlementRailView[] = resp.records
    .filter((r) => !input.currency || r.value.currency === input.currency)
    .filter((r) => !input.enabledOnly || r.value.enabled)
    .map((r) => ({ ...r.value, railUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Payment ledger entry (E2E-ENCRYPTED, PII) ──────────────────────

export async function recordPayment(e: Etzhayyim, input: RecordPaymentInput): Promise<RecordPaymentOutput> {
  if (!input.paymentId || !input.payerDid || !input.payeeDid || !input.currency) return { status: "rejected", error: "missingRequiredFields" };
  if (!isDecimalString(input.amount)) return { status: "rejected", error: "invalidAmount" };
  if (!isUint(input.amountMinor)) return { status: "rejected", error: "invalidAmountMinor" };
  if (input.status !== undefined && !isPaymentStatus(input.status)) return { status: "rejected", error: "invalidStatus" };
  const body: PaymentBody = {
    paymentId: input.paymentId,
    payerDid: input.payerDid,
    payeeDid: input.payeeDid,
    amount: input.amount,
    amountMinor: input.amountMinor,
    currency: input.currency,
    status: input.status ?? "pending",
    railId: input.railId,
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.encryptedWrite<Record<string, unknown>>({
    innerType: PAYMENT_INNER_TYPE,
    record: body as unknown as Record<string, unknown>,
    recipients: input.recipients ?? [],
    rkey: paymentRkey(input.paymentId),
  });
  return { status: "recorded", uri: receipt.uri, keyId: receipt.keyId, paymentId: input.paymentId };
}

async function scanPayments(e: Etzhayyim, maxScan: number): Promise<PaymentView[]> {
  const out: PaymentView[] = [];
  let cursor: string | undefined;
  while (out.length < maxScan) {
    const page = await e.encryptedRead<PaymentBody>({ innerType: PAYMENT_INNER_TYPE, cursor, limit: PAGE_LIMIT });
    for (const r of page.records) {
      out.push({ ...r.value, uri: r.uri, sender: r.sender, envCreatedAt: r.createdAt });
    }
    if (!page.cursor || page.records.length === 0) break;
    cursor = page.cursor;
  }
  return out;
}

export async function getPayment(e: Etzhayyim, input: GetPaymentInput): Promise<GetPaymentOutput> {
  if (!input.paymentId) return { error: "invalidPaymentId" };
  const all = await scanPayments(e, DEFAULT_MAX_SCAN);
  const found = all.find((p) => p.paymentId === input.paymentId);
  if (!found) return { error: "notFound" };
  return { payment: found };
}

export async function listPayments(e: Etzhayyim, input: ListPaymentsInput = {}): Promise<ListPaymentsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const all = await scanPayments(e, DEFAULT_MAX_SCAN);
  const filtered = all
    .filter((p) => !input.payerDid || p.payerDid === input.payerDid)
    .filter((p) => !input.status || p.status === input.status);
  return { items: filtered.slice(0, limit), total: filtered.length };
}

// ─── Transaction-history entry (E2E-ENCRYPTED) ──────────────────────

export async function recordTransaction(e: Etzhayyim, input: RecordTransactionInput): Promise<RecordTransactionOutput> {
  if (!input.txId || !input.accountDid || !input.currency) return { status: "rejected", error: "missingRequiredFields" };
  if (!isTxDirection(input.direction)) return { status: "rejected", error: "invalidDirection" };
  if (!isDecimalString(input.amount)) return { status: "rejected", error: "invalidAmount" };
  if (!isUint(input.amountMinor)) return { status: "rejected", error: "invalidAmountMinor" };
  const body: TransactionBody = {
    txId: input.txId,
    accountDid: input.accountDid,
    paymentId: input.paymentId,
    direction: input.direction,
    amount: input.amount,
    amountMinor: input.amountMinor,
    currency: input.currency,
    postedAt: input.postedAt ?? new Date().toISOString(),
  };
  const receipt = await e.encryptedWrite<Record<string, unknown>>({
    innerType: TRANSACTION_INNER_TYPE,
    record: body as unknown as Record<string, unknown>,
    recipients: input.recipients ?? [],
    rkey: transactionRkey(input.txId),
  });
  return { status: "recorded", uri: receipt.uri, keyId: receipt.keyId, txId: input.txId };
}

async function scanTransactions(e: Etzhayyim, maxScan: number): Promise<TransactionView[]> {
  const out: TransactionView[] = [];
  let cursor: string | undefined;
  while (out.length < maxScan) {
    const page = await e.encryptedRead<TransactionBody>({ innerType: TRANSACTION_INNER_TYPE, cursor, limit: PAGE_LIMIT });
    for (const r of page.records) {
      out.push({ ...r.value, uri: r.uri, sender: r.sender, envCreatedAt: r.createdAt });
    }
    if (!page.cursor || page.records.length === 0) break;
    cursor = page.cursor;
  }
  return out;
}

export async function listTransactions(e: Etzhayyim, input: ListTransactionsInput = {}): Promise<ListTransactionsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const all = await scanTransactions(e, DEFAULT_MAX_SCAN);
  const filtered = all
    .filter((t) => !input.accountDid || t.accountDid === input.accountDid)
    .filter((t) => !input.direction || t.direction === input.direction);
  return { items: filtered.slice(0, limit), total: filtered.length };
}

// ─── Account balance (E2E-ENCRYPTED, per-person) ────────────────────

export async function setBalance(e: Etzhayyim, input: SetBalanceInput): Promise<SetBalanceOutput> {
  if (!input.accountDid || !input.currency) return { status: "rejected", error: "missingRequiredFields" };
  if (!isDecimalString(input.amount)) return { status: "rejected", error: "invalidAmount" };
  if (!isUint(input.amountMinor)) return { status: "rejected", error: "invalidAmountMinor" };
  const body: BalanceBody = {
    accountDid: input.accountDid,
    currency: input.currency,
    amount: input.amount,
    amountMinor: input.amountMinor,
    asOf: input.asOf ?? new Date().toISOString(),
  };
  const receipt = await e.encryptedWrite<Record<string, unknown>>({
    innerType: BALANCE_INNER_TYPE,
    record: body as unknown as Record<string, unknown>,
    recipients: input.recipients ?? [],
    rkey: balanceRkey(input.accountDid, input.currency),
  });
  return { status: "recorded", uri: receipt.uri, keyId: receipt.keyId, accountDid: input.accountDid };
}

async function scanBalances(e: Etzhayyim, maxScan: number): Promise<BalanceView[]> {
  const out: BalanceView[] = [];
  let cursor: string | undefined;
  while (out.length < maxScan) {
    const page = await e.encryptedRead<BalanceBody>({ innerType: BALANCE_INNER_TYPE, cursor, limit: PAGE_LIMIT });
    for (const r of page.records) {
      out.push({ ...r.value, uri: r.uri, sender: r.sender, envCreatedAt: r.createdAt });
    }
    if (!page.cursor || page.records.length === 0) break;
    cursor = page.cursor;
  }
  return out;
}

export async function getBalance(e: Etzhayyim, input: GetBalanceInput): Promise<GetBalanceOutput> {
  if (!input.accountDid) return { error: "invalidAccountDid" };
  const all = await scanBalances(e, DEFAULT_MAX_SCAN);
  const found = all.find((b) => b.accountDid === input.accountDid && (!input.currency || b.currency === input.currency));
  if (!found) return { error: "notFound" };
  return { balance: found };
}

// ─── Coverage rollup ────────────────────────────────────────────────

export async function coverage(e: Etzhayyim, input: CoverageInput = {}): Promise<CoverageOutput> {
  const maxScan = Math.min(input.maxScan ?? DEFAULT_MAX_SCAN, DEFAULT_MAX_SCAN);
  const railsByCurrency: Record<string, number> = {};
  let settlementRailCount = 0;
  let cursor: string | undefined;
  while (settlementRailCount < maxScan) {
    const page = await e.read<SettlementRailRecord>({ collection: RAIL_COLLECTION, cursor, limit: PAGE_LIMIT });
    for (const r of page.records) {
      railsByCurrency[r.value.currency] = (railsByCurrency[r.value.currency] ?? 0) + 1;
      settlementRailCount += 1;
    }
    if (!page.cursor || page.records.length < PAGE_LIMIT) break;
    cursor = page.cursor;
  }
  const payments = await scanPayments(e, maxScan);
  const transactions = await scanTransactions(e, maxScan);
  const balances = await scanBalances(e, maxScan);
  const paymentsByStatus: Record<string, number> = {};
  // Exact integer minor-unit total — no float arithmetic on the decimal string.
  let paymentMinorTotal = 0;
  for (const p of payments) {
    paymentsByStatus[p.status] = (paymentsByStatus[p.status] ?? 0) + 1;
    paymentMinorTotal += p.amountMinor;
  }
  return {
    settlementRailCount,
    paymentCount: payments.length,
    transactionCount: transactions.length,
    balanceCount: balances.length,
    railsByCurrency,
    paymentsByStatus,
    paymentMinorTotal,
    truncated:
      settlementRailCount >= maxScan ||
      payments.length >= maxScan ||
      transactions.length >= maxScan ||
      balances.length >= maxScan,
  };
}
