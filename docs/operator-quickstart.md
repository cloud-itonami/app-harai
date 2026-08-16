# Operator quickstart — app-harai

**払い / harai — payment and settlement clearing.** Twenty-three files, 52,557 bytes,
in three layers that do not describe the same service:

| layer | what it is | does it run? |
|---|---|---|
| `kotoba/` | the ledger: a plaintext settlement-rail catalog plus E2E-encrypted payment / transaction / balance records | **yes** — 7 tests, all passing |
| `appview/*/svelte/` | a SvelteKit worker that forwards XRPC calls to an MCP router | **yes** — builds, and is what `wrangler.jsonc` deploys |
| `appview/*/src/app.ts` | an edge facade with `/health`, an nsid allow-list and body validation | **no** — 77 lines that no request reaches |

The third row is the thing to know before touching anything here. **The file that
looks like the service is not the file that runs**, and the two disagree about
whether a malformed payment request is an error.

Steps marked ✅ were run against this tree on 2026-08-16. Where a command's output
is quoted, it is the actual output.

---

## 1. Run the ledger tests ✅

```bash
cd kotoba && npm install && npm test
```

```
 ✓ test/harai.test.ts (7 tests) 4ms

 Test Files  1 passed (1)
      Tests  7 passed (7)
```

Seven tests cover the rail catalog (register / dedup / reject / get / filter), the
E2E payment path, read-cap enforcement (a non-recipient DID decrypts nothing),
transactions, balances and the coverage rollup.

**⚠ That `npm install` fails on npm 11.16.0.** Measured on three machines the same
day, same tree:

| npm | result |
|---|---|
| 10.9.7 | installs, 7 tests pass |
| **11.16.0** | **`EALLOWSCRIPTS` — `git dep preparation failed`** |
| 11.17.0 | installs, 7 tests pass |

Both dependencies are git URLs, and npm 11.16.0 refuses to run the `prepare` script
it needs to build one of them — the inner install it spawns rejects its own flags.
It is not a defect in this repository and not a general npm-11 problem: 11.17.0 is
fine. If you hit it, upgrade npm rather than editing the manifest.

Worth knowing while you are in there: **both dependencies have moved.**
`package.json` points at `etzhayyim/com-etzhayyim-sdk` and `…-sdk-mock`, which now
redirect to `kotoba-lang/sdk` and `kotoba-lang/sdk-mock`. The pinned commits still
resolve, so nothing is broken today; it is a redirect this repository is relying on
without saying so.

## 2. Build what actually deploys ✅

```bash
cd appview/harai-mcp-component/svelte && npm install && npm run build
```

Builds clean and produces `.svelte-kit/cloudflare/_worker.js` — the path
`wrangler.jsonc` names as `main`. Its route table is exactly two entries:

```
id: "/"
id: "/xrpc/[...path]"
```

There is no `/health`. The string does not occur in the deployed bundle at all
(`grep -c health .svelte-kit/cloudflare/_worker.js` → `0`), and with
`not_found_handling: "none"` a health check against this service gets SvelteKit's
404. Anything monitoring `/health` here is monitoring nothing.

## 3. The two request paths, side by side ✅

`src/app.ts` has no imports and uses only `Request`/`Response`, so Node runs it
directly. Walked on Node v26.3.0, where `--experimental-strip-types` is a no-op
(default since Node 23) and required on 22.6–22.x:

```bash
cd appview/harai-mcp-component

cat > /tmp/hwalk.mjs <<'EOF'
const app = (await import(process.argv[2])).default;
const env = { DISPATCHER_URL: "http://127.0.0.1:9/unreachable" };
const H = "https://harcom.etzhayyim.ai";
for (const [l, req] of [
  ["GET /health          ", new Request(`${H}/health`)],
  ["GET /nope            ", new Request(`${H}/nope`)],
  ["POST bad json (harai)", new Request(`${H}/xrpc/com.etzhayyim.apps.harai.listPayments`,
                                        { method: "POST", body: "{not json" })],
  ["POST foreign nsid    ", new Request(`${H}/xrpc/com.example.someoneElse.doThing`,
                                        { method: "POST", body: "{}" })],
]) {
  const r = await app.fetch(req, env);
  console.log(l, "->", r.status, (await r.text()).slice(0, 100));
}
EOF

node --experimental-strip-types /tmp/hwalk.mjs "$PWD/src/app.ts"
```

Actual output:

```
GET /health           -> 200 {"ok":true,"actor":"did:web:harcom.etzhayyim.ai", …
GET /nope             -> 404 {"error":"NotFound"}
POST bad json (harai) -> 400 {"error":"InvalidJson"}
POST foreign nsid     -> 404 {"error":"NotFound"}
```

Now the same requests through the **built** endpoint — the one that deploys. This
needs §2 to have run first; the MCP router is stubbed so the forwarded call can be
inspected rather than sent:

```bash
cd appview/harai-mcp-component/svelte

cat > /tmp/dprobe.mjs <<'EOF'
const mod = await import(process.argv[2]);
let captured = null;
globalThis.fetch = async (url, init) => {
  captured = { url, body: JSON.parse(init.body) };
  return new Response(JSON.stringify({ jsonrpc: "2.0", id: 1,
                        result: { structuredContent: { echoed: true } } }),
                      { status: 200, headers: { "content-type": "application/json" } });
};
const mkEvent = (nsid, raw) => ({
  params: { path: nsid },
  request: new Request("https://x/xrpc/" + nsid,
             { method: "POST", body: raw, headers: { "content-type": "application/json" } }),
  platform: { env: {} },
});
for (const [label, nsid, raw] of [
  ["well-formed harai  ", "com.etzhayyim.apps.harai.listPayments", '{"payerDid":"did:web:alice"}'],
  ["MALFORMED body     ", "com.etzhayyim.apps.harai.listPayments", '{not json'],
  ["FOREIGN nsid       ", "com.example.someoneElse.doThing",       '{"x":1}'],
]) {
  captured = null;
  const r = await mod.POST(mkEvent(nsid, raw));
  console.log(label, "-> HTTP", r.status, "| tool:", captured?.body?.params?.name,
              "| arguments:", JSON.stringify(captured?.body?.params?.arguments));
}
EOF

node /tmp/dprobe.mjs "$PWD/.svelte-kit/output/server/entries/endpoints/xrpc/_...path_/_server.ts.js"
```

Actual output:

```
well-formed harai   -> HTTP 200 | tool: com.etzhayyim.apps.harai.listPayments | arguments: {"payerDid":"did:web:alice"}
MALFORMED body      -> HTTP 200 | tool: com.etzhayyim.apps.harai.listPayments | arguments: {}
FOREIGN nsid        -> HTTP 200 | tool: com.example.someoneElse.doThing | arguments: {"x":1}
```

Two differences, both load-bearing for a payments service:

- **A corrupt request body becomes a successful call with no arguments.** The
  deployed route does `.catch(() => ({}))`, so `{not json` posted at `listPayments`
  is not rejected — it is forwarded as `listPayments({})` and answered `200`. The
  facade's `400 InvalidJson` is not in the deployed path.
- **The nsid allow-list is not in the deployed path either.** The facade only
  proxies `com.etzhayyim.apps.harai.*`; the deployed route takes whatever path
  segment the caller sends and asks the MCP router to run a tool by that name.
  `com.example.someoneElse.doThing` went straight through.

The workspace's own `verify-appview-facade` reaches the first two conclusions
independently and names this repository, along with `orgs/cloud-itonami/shiharai`
and `orgs/etzhayyim/com-etzhayyim-app-harai`, which have the same shape.

## 4. Five declared methods have no implementation ✅

Four surfaces declare the method vocabulary and **all four agree** — `kotodama.jsonld`
`profile.capabilities`, `wrangler.jsonc` `APP_CAPABILITIES`, the eight `serviceTask`
definitions in `bpmn/harai-control.bpmn`, and the `methods` list in the facade's
`/health` body. All eight names, four times over:

```
closeAccount createPayment getBalance listPayments
listTransactions refundPayment settlePayment transferFunds
```

`kotoba/src/index.ts` exports eleven functions. The overlap is **three**:

| | |
|---|---|
| declared **and** implemented (3) | `getBalance` `listPayments` `listTransactions` |
| declared, **no implementation anywhere** (5) | `closeAccount` `createPayment` `refundPayment` `settlePayment` `transferFunds` |
| implemented, **undeclared** (8) | `coverage` `getPayment` `getRail` `listRails` `recordPayment` `recordTransaction` `registerRail` `setBalance` |

The naming suggests the two halves were designed against different models —
`createPayment` versus `recordPayment`, and a rail catalog the declarations never
mention. Nothing in the tree resolves which is intended; see §7.

## 5. The actor's hostname is a codemod artifact ✅

Every identity in this repository points at `harcom.etzhayyim.ai` — the
`kotodama.jsonld` `@id`, `ACTOR_DID` in the facade, `HARAI_DID_PREFIX` in
`kotoba/src/types.ts` (which mints a DID per settlement rail), and a `wrangler.jsonc`
route. **That domain does not exist.** Not "the host is down" — `etzhayyim.ai` has no
NS records and NXDOMAINs at the apex, so `did:web:harcom.etzhayyim.ai` can never
resolve.

The name looks like the output of a rewrite intended to move `etzhayyim.ai` to
`etzhayyim.com`, which instead replaced the first `ai.` inside the app's own name:

```
harai.etzhayyim.ai      ->  harcom.etzhayyim.ai
shiharai.etzhayyim.ai   ->  shiharcom.etzhayyim.ai
```

Both mangled names in this fleet are reproduced exactly by that one rule. The
sibling repository settles it: `orgs/cloud-itonami/shiharai` still carries
`did:web:shiharai.etzhayyim.com` in its `kotodama.jsonld`, its facade and its
`CLAUDE.md`, and has the mangled form in **only one place — the wrangler route**. So
the intended host here was almost certainly `harai.etzhayyim.com`.

That also explains a config that is otherwise simply invalid:

```jsonc
{ "pattern": "harcom.etzhayyim.ai/*", "zone_name": "etzhayyim.com" }
```

A route pattern's hostname has to sit inside its zone, and this one is in a
different TLD. With the intended name it would be `harai.etzhayyim.com/*` in zone
`etzhayyim.com`, which is consistent. **`harai.etzhayyim.com` does not resolve
today either**, so fixing the string is necessary but not sufficient — the record
has to exist before any of this serves traffic.

## 6. The firehose subscription cannot fire ✅

`kotodama.jsonld` subscribes to two collections:

```
com.etzhayyim.apps.harai.payment
com.etzhayyim.apps.harai.transaction
```

Neither is ever written as a collection. `registry.ts` writes payments,
transactions and balances through `encryptedWrite`, where those NSIDs are the
**`innerType`** — routing metadata *inside* an envelope whose outer collection is
`com.etzhayyim.encrypted.record` (the SDK's default; `registry.ts` never overrides
it). A repo subscription matches the outer collection, so these two never match.

Meanwhile `com.etzhayyim.apps.harai.settlementRail` — the one collection actually
written as an outer collection, via `e.write` — is **not** subscribed.

This is a consequence of the E2E design working as intended, not a bug in it: the
substrate is not supposed to see payer DIDs or amounts. But it does mean the
declared trigger is inert, and a subscriber expecting payment events will wait
forever.

## 7. Re-run all of the above ✅

```bash
nbb scripts/verify-harai-surface.cljs            # ~1s, needs DNS for the last check
nbb scripts/verify-harai-surface.cljs --no-net   # skips it, and says so
```

Nine assertions, one per finding above:

```
SCANNED	9	harai-surface
  held    deployed-entry-is-sveltekit-not-app-ts
  held    health-served-only-by-undeployed-facade
  held    malformed-body-rejected-only-by-undeployed-facade
  held    nsid-prefix-enforced-only-by-undeployed-facade
  held    four-declaration-surfaces-agree
  held    declared-vs-implemented-overlap
  held    wrangler-route-outside-its-declared-zone
  held    subscribed-collections-are-inner-types-only
  held    actor-did-domain-does-not-resolve

held 9 / changed 0 / skipped 0 of 9
```

**It pins the present, and it does not pick a winner.** Fixing any of these turns it
red on purpose — the assertion and the fix belong in the same commit, so that
choosing a canonical surface is a recorded decision rather than a drift. Exit codes
are three-valued: `0` held, `1` something moved, `2` could not answer (a missing
input, or fewer than nine assertions — a checker that scans nothing must not report
clean).

It was checked against deliberately broken copies before being written down:

```bash
nbb scripts/mutate-harai-surface.cljs
#   14 / 14 demonstrations passed
```

Eleven mutations, each required to produce exit 1 **and to name exactly the
assertion it should have moved** — exit code alone would accept a checker that goes
red for an unrelated reason. Plus three floors: an untouched copy is silent, a
removed input exits 2, and `--no-net` reports the DNS check as *skipped* rather than
held.

## 8. What is not decided here

Three questions this document deliberately does not answer, because they are
product decisions and the tree contains no basis for choosing:

1. **Which method vocabulary is real** — the eight declared (and drawn as BPMN
   processes), or the eleven implemented? Five declared methods, including
   `createPayment` and `settlePayment`, exist nowhere as code.
2. **Which request path is the service** — the validating facade or the deployed
   pass-through? Repointing `wrangler.jsonc` at `src/app.ts` is a one-line change,
   but the facade proxies to a `DISPATCHER_URL` while the SvelteKit route speaks
   MCP tool-calls to a different upstream. They are not interchangeable.
3. **What the actor is called.** `harai.etzhayyim.com` is the evidenced intent, but
   nothing resolves yet, and `HARAI_DID_PREFIX` has been minting rail DIDs under
   the mangled name.

Until (2) is settled, the deployed behaviour stands: a malformed body at a payment
method returns `200`.

## 9. What the maturity instrument sees

```
· orgs/cloud-itonami/app-harai  own=0.049  axis-docs=0bp → +2500bp
```

The README component reads 0 because the instrument reads `README.md` and this
repository declares `README.edn` (`:canonical-metadata :edn`); `axis-substrate`
reads 0 because it counts a top-level `src/`, and the code here lives under
`kotoba/src/` and `appview/*/src/`. Both are measurement conventions rather than
findings — the scan reports them separately as `uncounted/*` and does not fold them
into the score (ADR-2608052000).
