import { describe, it, expect, beforeEach } from "vitest";
import { MockEtzhayyim } from "@etzhayyim/sdk-mock";
import {
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
} from "../src/index.js";

const OWNER = "did:web:harcom.etzhayyim.ai";

describe("harai kotoba (payment & settlement clearing, E2E split)", () => {
  let e: any;
  beforeEach(() => {
    e = new MockEtzhayyim({ did: OWNER });
  });

  describe("settlementRail (PLAINTEXT public catalog)", () => {
    it("registers, dedups, validates, gets, lists/filters", async () => {
      expect((await registerRail(e, { railId: "r1", currency: "USD", railLabel: "domestic-wire", minorUnitExp: 2 })).status).toBe("registered");
      expect((await registerRail(e, { railId: "r1", currency: "USD", railLabel: "domestic-wire", minorUnitExp: 2 })).status).toBe("alreadyExists");
      expect((await registerRail(e, { railId: "rX", currency: "USD", railLabel: "bad", minorUnitExp: -1 })).status).toBe("rejected");
      expect((await registerRail(e, { railId: "rY", currency: "", railLabel: "bad", minorUnitExp: 2 })).status).toBe("rejected");
      await registerRail(e, { railId: "r2", currency: "JPY", railLabel: "on-chain-usdc", enabled: false, minorUnitExp: 0 });
      const got = await getRail(e, { railId: "r1" });
      expect(got.rail?.currency).toBe("USD");
      expect(got.rail?.minorUnitExp).toBe(2);
      expect((await listRails(e)).total).toBe(2);
      expect((await listRails(e, { currency: "USD" })).total).toBe(1);
      expect((await listRails(e, { enabledOnly: true })).total).toBe(1);
      expect((await getRail(e, { railId: "nope" })).error).toBe("notFound");
    });
  });

  describe("payment (E2E-ENCRYPTED ledger entry, PII)", () => {
    it("seals via encryptedWrite, round-trips via encryptedRead, validates", async () => {
      const ok = await recordPayment(e, {
        paymentId: "p1", payerDid: "did:web:alice", payeeDid: "did:web:bob",
        amount: "100.50", amountMinor: 10050, currency: "USD", status: "pending",
      });
      expect(ok.status).toBe("recorded");
      expect(ok.keyId).toBeTruthy();
      // float / non-decimal amount rejected
      expect((await recordPayment(e, { paymentId: "pX", payerDid: "a", payeeDid: "b", amount: "abc", amountMinor: 1, currency: "USD" })).status).toBe("rejected");
      // negative minor rejected
      expect((await recordPayment(e, { paymentId: "pY", payerDid: "a", payeeDid: "b", amount: "1.00", amountMinor: -1, currency: "USD" })).status).toBe("rejected");
      // bad status rejected
      expect((await recordPayment(e, { paymentId: "pZ", payerDid: "a", payeeDid: "b", amount: "1.00", amountMinor: 100, currency: "USD", status: "weird" as any })).status).toBe("rejected");
      const got = await getPayment(e, { paymentId: "p1" });
      expect(got.payment?.payerDid).toBe("did:web:alice");
      expect(got.payment?.amount).toBe("100.50");
      expect(got.payment?.amountMinor).toBe(10050);
      await recordPayment(e, { paymentId: "p2", payerDid: "did:web:carol", payeeDid: "did:web:bob", amount: "5.00", amountMinor: 500, currency: "USD", status: "settled" });
      expect((await listPayments(e)).total).toBe(2);
      expect((await listPayments(e, { payerDid: "did:web:alice" })).total).toBe(1);
      expect((await listPayments(e, { status: "settled" })).total).toBe(1);
    });

    it("enforces read-cap: a non-recipient DID cannot decrypt the payment", async () => {
      await recordPayment(e, { paymentId: "p1", payerDid: "did:web:alice", payeeDid: "did:web:bob", amount: "9.99", amountMinor: 999, currency: "USD" });
      const outsider: any = new MockEtzhayyim({ did: "did:web:outsider.example" });
      expect((await listPayments(outsider)).total).toBe(0);
    });

    it("grants read-cap to an explicit recipient", async () => {
      const r = await recordPayment(e, { paymentId: "p1", payerDid: "did:web:alice", payeeDid: "did:web:bob", amount: "9.99", amountMinor: 999, currency: "USD", recipients: ["did:web:partner.example"] });
      expect(r.status).toBe("recorded");
      expect((await listPayments(e)).total).toBe(1);
    });
  });

  describe("transaction (E2E-ENCRYPTED ledger movement)", () => {
    it("records, validates direction/amount, lists/filters", async () => {
      expect((await recordTransaction(e, { txId: "t1", accountDid: "did:web:alice", direction: "debit", amount: "100.50", amountMinor: 10050, currency: "USD", paymentId: "p1" })).status).toBe("recorded");
      expect((await recordTransaction(e, { txId: "tX", accountDid: "a", direction: "sideways" as any, amount: "1.00", amountMinor: 100, currency: "USD" })).status).toBe("rejected");
      expect((await recordTransaction(e, { txId: "tY", accountDid: "a", direction: "credit", amount: "x", amountMinor: 1, currency: "USD" })).status).toBe("rejected");
      await recordTransaction(e, { txId: "t2", accountDid: "did:web:bob", direction: "credit", amount: "100.50", amountMinor: 10050, currency: "USD" });
      expect((await listTransactions(e)).total).toBe(2);
      expect((await listTransactions(e, { accountDid: "did:web:alice" })).total).toBe(1);
      expect((await listTransactions(e, { direction: "credit" })).total).toBe(1);
    });
  });

  describe("balance (E2E-ENCRYPTED, per-person owner-written)", () => {
    it("sets and gets a per-account balance snapshot", async () => {
      expect((await setBalance(e, { accountDid: "did:web:alice", currency: "USD", amount: "250.00", amountMinor: 25000 })).status).toBe("recorded");
      expect((await setBalance(e, { accountDid: "a", currency: "USD", amount: "bad", amountMinor: 1 })).status).toBe("rejected");
      const got = await getBalance(e, { accountDid: "did:web:alice", currency: "USD" });
      expect(got.balance?.amount).toBe("250.00");
      expect(got.balance?.amountMinor).toBe(25000);
      expect((await getBalance(e, { accountDid: "did:web:nobody" })).error).toBe("notFound");
    });
  });

  describe("coverage rollup", () => {
    it("counts plaintext rails + E2E ledger and sums minor units exactly (no float)", async () => {
      await registerRail(e, { railId: "r1", currency: "USD", railLabel: "domestic-wire", minorUnitExp: 2 });
      await registerRail(e, { railId: "r2", currency: "USD", railLabel: "on-chain-usdc", minorUnitExp: 2 });
      await recordPayment(e, { paymentId: "p1", payerDid: "did:web:alice", payeeDid: "did:web:bob", amount: "100.50", amountMinor: 10050, currency: "USD", status: "settled" });
      await recordPayment(e, { paymentId: "p2", payerDid: "did:web:carol", payeeDid: "did:web:bob", amount: "0.01", amountMinor: 1, currency: "USD", status: "pending" });
      await recordTransaction(e, { txId: "t1", accountDid: "did:web:alice", direction: "debit", amount: "100.50", amountMinor: 10050, currency: "USD" });
      await setBalance(e, { accountDid: "did:web:alice", currency: "USD", amount: "149.50", amountMinor: 14950 });
      const cov = await coverage(e);
      expect(cov.settlementRailCount).toBe(2);
      expect(cov.paymentCount).toBe(2);
      expect(cov.transactionCount).toBe(1);
      expect(cov.balanceCount).toBe(1);
      expect(cov.railsByCurrency?.USD).toBe(2);
      expect(cov.paymentsByStatus?.settled).toBe(1);
      expect(cov.paymentsByStatus?.pending).toBe(1);
      // exact integer math: 10050 + 1 = 10051 (no float drift)
      expect(cov.paymentMinorTotal).toBe(10051);
      expect(cov.truncated).toBe(false);
    });
  });
});
