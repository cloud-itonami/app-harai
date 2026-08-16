# Migration TODO — etzhayyim-project-harai

**Status**: 🔄 TRANSFORM — thin-edge appview migrated from etzhayyim archive 2026-06-01.

This is a thin-edge dispatcher (edge-proxy → AgentGateway MCP → pod-side LangServer).
No worker-side RisingWave/fiat dependency; business logic runs in the dispatcher/pod.

**Codemod pending** (substrate-boundary ADR-2605172000 / 2605172100):
- Confirm `DISPATCHER_URL` targets an etzhayyim-substrate dispatcher (kotoba).
- Any settlement path → USDC + ERC-4337 (no Stripe/fiat).
- ~~appview wiring + `kotoba/` reference slice TBD.~~ **The `kotoba/` slice landed**
  (rail catalog + E2E payment/transaction/balance registry, 7 passing tests). The
  appview is wired and deploys. What is *not* settled is which of the two request
  paths is the service, and which method vocabulary is real — the declarations and
  the implementation overlap in only 3 of 8 names. See `docs/operator-quickstart.md`
  §3–§4 and §8, and `scripts/verify-harai-surface.cljs`, which pins the current
  state so it cannot drift further unnoticed.

**One codemod artifact is confirmed and unfixed**: every identity here points at
`harcom.etzhayyim.ai`, a domain that does not exist. The evidenced intent is
`harai.etzhayyim.com` (quickstart §5). Renaming it is a decision, not a cleanup —
`HARAI_DID_PREFIX` has been minting per-rail DIDs under the mangled name.
