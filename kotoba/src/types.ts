/**
 * harai kotoba — payment & settlement clearing, kotoba-E2E split.
 *
 * Per ADR-2606011400 (Consensys product-front / infra-back) + ADR-2605172400
 * (3-axis) + ADR-2605172100 (operating-entity boundary: etzhayyim never becomes
 * the fiat merchant-of-record / counterparty — on-chain USDC only) + ADR-2605181100
 * (kotoba E2E encrypted-record envelope). Founder directive 2026-06-03: maximal
 * migration — front everything that can move; only the irreducible regulated
 * EXECUTION stays etzhayyim.
 *
 * SPLIT:
 *   PUBLIC (plaintext AT records) — settlement-rail catalog: reference metadata
 *   for supported currencies / clearing rails (currency code, rail label, enabled
 *   flag, minor-unit exponent). No per-person data, no money sums. Frontable open
 *   reference + read-view + coverage counts.
 *
 *   PII / LEDGER-ENTRIES (kotoba E2E, com.etzhayyim.encrypted.record) — payment
 *   ledger entries (payer/payee DID, amount, status) and transaction-history
 *   entries (per-account movements) and account balances (per-person). Sealed via
 *   sdk.encryptedWrite (read-cap = owner DID + explicit recipients), so the
 *   financial ledger lives on-substrate encrypted, never etzhayyim-resident plaintext.
 *
 *   STAYS etzhayyim (consumed via consent-capability — NOT a collection) — the
 *   irreducible regulated EXECUTION: the fiat merchant-of-record settlement /
 *   clearing rail CALL (the act of moving real-world money). The DATA migrates as
 *   E2E records; only the regulated settlement execution stays etzhayyim, invoked under
 *   consent-capability. etzhayyim is never the fiat counterparty.
 *
 * AT-Lexicon: no float. Money is a decimal STRING (`amount`) carried with an
 * integer minor-unit field (`amountMinor`, e.g. cents) so any rollup uses exact
 * integer math — never parseFloat. minorUnitExp / counts are integers.
 */

// Plaintext public collection.
export const RAIL_COLLECTION = "com.etzhayyim.apps.harai.settlementRail";
// E2E inner-type NSIDs (= the two declared harai collections).
export const PAYMENT_INNER_TYPE = "com.etzhayyim.apps.harai.payment";
export const TRANSACTION_INNER_TYPE = "com.etzhayyim.apps.harai.transaction";
export const BALANCE_INNER_TYPE = "com.etzhayyim.apps.harai.balance";

export const HARAI_DID_PREFIX = "did:web:harcom.etzhayyim.ai:" as const;

export type PaymentStatus = "pending" | "settled" | "refunded" | "failed";
export type TxDirection = "debit" | "credit";

// ─── Settlement-rail catalog (PLAINTEXT, public reference) ───────────

export interface SettlementRailRecord {
  did: string;
  railId: string;
  currency: string;
  /** Human label of the clearing rail (e.g. "domestic-wire", "on-chain-usdc"). */
  railLabel: string;
  enabled: boolean;
  /** Integer exponent for the currency minor unit (e.g. 2 for cents, 0 for JPY). */
  minorUnitExp: number;
  createdAt: string;
}
export interface SettlementRailView extends SettlementRailRecord {
  railUri: string;
}
export interface RegisterRailInput {
  railId: string;
  currency: string;
  railLabel: string;
  enabled?: boolean;
  minorUnitExp: number;
}
export interface RegisterRailOutput {
  status: "registered" | "alreadyExists" | "rejected";
  railUri?: string;
  did?: string;
  railId?: string;
  error?: string;
}
export interface GetRailInput {
  railId: string;
}
export interface GetRailOutput {
  rail?: SettlementRailView;
  error?: string;
}
export interface ListRailsInput {
  currency?: string;
  enabledOnly?: boolean;
  limit?: number;
  cursor?: string;
}
export interface ListRailsOutput {
  items: SettlementRailView[];
  cursor?: string;
  total: number;
}

// ─── Payment ledger entry (E2E-ENCRYPTED, PII) ──────────────────────

export interface PaymentBody {
  paymentId: string;
  payerDid: string;
  payeeDid: string;
  /** Decimal string, e.g. "100.50". NEVER a number. */
  amount: string;
  /** Exact integer minor units (e.g. cents) for safe summation. */
  amountMinor: number;
  currency: string;
  status: PaymentStatus;
  /** Optional FK to a settlement-rail catalog entry. */
  railId?: string;
  createdAt: string;
}
export interface PaymentView extends PaymentBody {
  uri: string;
  sender: string;
  envCreatedAt: string;
}
export interface RecordPaymentInput {
  paymentId: string;
  payerDid: string;
  payeeDid: string;
  amount: string;
  amountMinor: number;
  currency: string;
  status?: PaymentStatus;
  railId?: string;
  /** Extra DIDs to grant read-cap (owner always included). */
  recipients?: string[];
}
export interface RecordPaymentOutput {
  status: "recorded" | "rejected";
  uri?: string;
  keyId?: string;
  paymentId?: string;
  error?: string;
}
export interface GetPaymentInput {
  paymentId: string;
}
export interface GetPaymentOutput {
  payment?: PaymentView;
  error?: string;
}
export interface ListPaymentsInput {
  payerDid?: string;
  status?: PaymentStatus;
  limit?: number;
  cursor?: string;
}
export interface ListPaymentsOutput {
  items: PaymentView[];
  cursor?: string;
  total: number;
}

// ─── Transaction-history entry (E2E-ENCRYPTED, ledger movement) ──────

export interface TransactionBody {
  txId: string;
  accountDid: string;
  /** FK to a payment ledger entry (optional). */
  paymentId?: string;
  direction: TxDirection;
  amount: string;
  amountMinor: number;
  currency: string;
  postedAt: string;
}
export interface TransactionView extends TransactionBody {
  uri: string;
  sender: string;
  envCreatedAt: string;
}
export interface RecordTransactionInput {
  txId: string;
  accountDid: string;
  paymentId?: string;
  direction: TxDirection;
  amount: string;
  amountMinor: number;
  currency: string;
  postedAt?: string;
  recipients?: string[];
}
export interface RecordTransactionOutput {
  status: "recorded" | "rejected";
  uri?: string;
  keyId?: string;
  txId?: string;
  error?: string;
}
export interface ListTransactionsInput {
  accountDid?: string;
  direction?: TxDirection;
  limit?: number;
  cursor?: string;
}
export interface ListTransactionsOutput {
  items: TransactionView[];
  cursor?: string;
  total: number;
}

// ─── Account balance (E2E-ENCRYPTED, per-person) ────────────────────

export interface BalanceBody {
  accountDid: string;
  currency: string;
  /** Decimal string snapshot, owner-written (no server-side computation). */
  amount: string;
  amountMinor: number;
  asOf: string;
}
export interface BalanceView extends BalanceBody {
  uri: string;
  sender: string;
  envCreatedAt: string;
}
export interface SetBalanceInput {
  accountDid: string;
  currency: string;
  amount: string;
  amountMinor: number;
  asOf?: string;
  recipients?: string[];
}
export interface SetBalanceOutput {
  status: "recorded" | "rejected";
  uri?: string;
  keyId?: string;
  accountDid?: string;
  error?: string;
}
export interface GetBalanceInput {
  accountDid: string;
  currency?: string;
}
export interface GetBalanceOutput {
  balance?: BalanceView;
  error?: string;
}

// ─── Coverage rollup ────────────────────────────────────────────────

export interface CoverageInput {
  maxScan?: number;
}
export interface CoverageOutput {
  settlementRailCount?: number;
  paymentCount?: number;
  transactionCount?: number;
  balanceCount?: number;
  railsByCurrency?: Record<string, number>;
  paymentsByStatus?: Record<string, number>;
  /** Exact integer minor-unit total of all readable payments (no float). */
  paymentMinorTotal?: number;
  truncated?: boolean;
  error?: string;
}

// ─── Validation + helpers ───────────────────────────────────────────

const PAYMENT_STATUSES: readonly PaymentStatus[] = ["pending", "settled", "refunded", "failed"];
const TX_DIRECTIONS: readonly TxDirection[] = ["debit", "credit"];

export function isUint(n: unknown): n is number {
  return typeof n === "number" && Number.isInteger(n) && n >= 0;
}
export function isPaymentStatus(s: unknown): s is PaymentStatus {
  return typeof s === "string" && (PAYMENT_STATUSES as readonly string[]).includes(s);
}
export function isTxDirection(d: unknown): d is TxDirection {
  return typeof d === "string" && (TX_DIRECTIONS as readonly string[]).includes(d);
}
/** Decimal money string: digits, optional single dot, optional fractional part. */
export function isDecimalString(s: unknown): s is string {
  return typeof s === "string" && /^\d+(\.\d+)?$/.test(s);
}
export function railDidFor(id: string): string {
  return `${HARAI_DID_PREFIX}rail:${id.toLowerCase()}`;
}
export function railRkey(id: string): string {
  return `rail-${id.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`;
}
export function paymentRkey(id: string): string {
  return `pay-${id.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`;
}
export function transactionRkey(id: string): string {
  return `tx-${id.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`;
}
export function balanceRkey(accountDid: string, currency: string): string {
  return `bal-${`${accountDid}-${currency}`.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`;
}
