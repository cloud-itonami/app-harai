# Migration TODO — etzhayyim-project-harai

**Status**: 🔄 TRANSFORM — thin-edge appview migrated from etzhayyim archive 2026-06-01.

This is a thin-edge dispatcher (edge-proxy → AgentGateway MCP → pod-side LangServer).
No worker-side RisingWave/fiat dependency; business logic runs in the dispatcher/pod.

**Codemod pending** (substrate-boundary ADR-2605172000 / 2605172100):
- Confirm `DISPATCHER_URL` targets an etzhayyim-substrate dispatcher (kotoba).
- Any settlement path → USDC + ERC-4337 (no Stripe/fiat).
- ~~appview wiring + `kotoba/` reference slice TBD.~~ **The `kotoba/` slice landed**
  (rail catalog + E2E payment/transaction/balance registry, 7 tests). The appview
  is wired and deploys.

**The appview is ClojureScript as of 2026-08-18** (`docs/adr/0001`). It was
TypeScript in two disagreeing copies — a SvelteKit worker that deployed, and a
facade (`src/app.ts`) that read like the service and that no request reached. The
deployed behaviour was ported to `src/harai/{route.cljc,view.cljc,worker.cljs}`
and both TypeScript copies were removed, so **there is one request path now**.
`kotoba/` stayed exactly as it was: it is in no bundle and the appview never
referenced it, so it was not the migration's to touch.

What is still *not* settled is **which method vocabulary is real** — the eight
names the declarations agree on, or the eleven `kotoba/src` exports, which overlap
in only three. See `docs/operator-quickstart.md` §8 and §11, and
`scripts/verify-harai-surface.cljs`, which pins the current state so it cannot
drift further unnoticed.

**One codemod artifact is confirmed and unfixed**: every identity here points at
`harcom.etzhayyim.ai`, a domain that does not exist. The evidenced intent is
`harai.etzhayyim.com` (quickstart §9). Renaming it is a decision, not a cleanup —
`HARAI_DID_PREFIX` has been minting per-rail DIDs under the mangled name.
