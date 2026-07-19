/**
 * harai kotoba — barrel. Payment & settlement clearing, kotoba-E2E split
 * (plaintext settlement-rail catalog + E2E payment/transaction/balance ledger,
 * ADR-2605181100). The fiat merchant-of-record settlement EXECUTION stays etzhayyim
 * and is consumed via consent-capability; the ledger DATA migrates here.
 */
export * from "./types.js";
export {
  registerRail,
  getRail,
  listRails,
  recordPayment,
  getPayment,
  listPayments,
  recordTransaction,
  listTransactions,
  setBalance,
  getBalance,
  coverage,
} from "./registry.js";
