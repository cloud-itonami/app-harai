# app-harai

**払い（harai）—— 決済・清算（payment & settlement clearing）の appview。**
名前が機能を示さないので先に名乗る（この workspace の規約）。この repo が持つのは
**公開面（appview）と台帳の実装（`kotoba/`）**であって、決済の実行そのものではない。

`etzhayyim/root` の `60-apps/etzhayyim-project-harai` からの抽出物で、
**2026-08-18 に appview を TypeScript/Svelte から ClojureScript へ移行した**
（`docs/adr/0001`）。ここに書いた数字はすべて
`scripts/verify-harai-surface.cljk` が tree から再計算して検査する。

## deploy されるものは、いま読んでいるソースである

```
src/harai/route.cljk    判断（どの handler が答えるか）  ← 純 .cljc、テスト対象
src/harai/view.cljk     ページ（jp-go-dds の hiccup）    ← 純 .cljc、テスト対象
src/harai/worker.cljk   Request/Response に触る唯一の層
        ↓ shadow-cljs :target :esm
dist/worker.js          ← wrangler.jsonc の "main" が指すもの
```

移行前は `main` が SvelteKit のビルド出力（`svelte/.svelte-kit/cloudflare/_worker.js`）
を指し、読み手が開く `appview/harai-mcp-component/src/app.ts` は **どの bundle にも
入っていなかった**。今回それを実測した:

| 測ったもの | 実測値 |
|---|---|
| SvelteKit をビルドした `_worker.js` の route id | `"/"` と `"/xrpc/[...path]"` の 2 本だけ |
| 同 bundle 内の `health` | **0 件** —— facade の `/health` はどの request も届かない |
| 同 bundle 内の `kotoba/` 由来の識別子（`settlementRail` 等 5 語） | **すべて 0 件** |

いまは `main` が指す bundle が上のソースからコンパイルされたものなので、
「読むファイル」と「動くファイル」がずれる形は構造的に起こり得ない。検証器は
**wrangler の `main` と shadow の出力先と export の ns 名の 3 つが噛み合っていること**
を検査し、噛み合わなくなれば落ちる。

## 公開ルート

| METHOD | PATH | 何をするか |
|---|---|---|
| GET | `/` | この appview の説明ページ |
| POST | `/xrpc/:nsid` | XRPC を AgentGateway の MCP router へ中継する |
| OPTIONS | `/xrpc/*` | CORS preflight（204） |

**この表の出所は `harai.route/routes` で、ページもそこから描く。** 移行前のページは
`routeCount: 0` / `routes: []` / `vars: []` を literal で持っており、同じディレクトリの
`wrangler.jsonc` が route 2 本・var 8 個を宣言していることに気づけなかった。

- `/health` は**無い**。移行前に deploy されていた面にも無かった（上表の実測）。
  持っていたのは request の届かない facade の方で、**移行で生やすのは移行ではない**。
- `/xrpc/a/b`（多段）は移行前の rest parameter `[...path]` と同じく**そのまま中継する**。
  1 セグメントに絞るのは移行ではなく方針変更なので、ここではしていない。
- 宣言外の nsid（`com.example.someoneElse.doThing`）も同じく中継する。allow-list を
  持っていたのは deploy されていない facade の方だった（実測は quickstart §3・§8）。

## 移行で持ち越さなかったもの（黙って消していない）

`src/app.ts` にあって、**どこにも deploy されていなかった**経路のうち:

| 経路 | 移していない理由（実測） |
|---|---|
| `/health` | deploy されていた bundle に `health` は 0 件。新設は移行ではない |
| nsid allow-list（`com.etzhayyim.apps.harai.*`） | 同上。deploy されていた route は任意の nsid を中継していた |
| 壊れた body の `400 InvalidJson` | 同上。deploy されていた route は `.catch(() => ({}))` で `{}` として通していた |
| `DISPATCHER_URL` への proxy | 宛先 `dispatcher.etzhayyim.com` が **NXDOMAIN**、かつ `DISPATCHER_URL` / `DISPATCHER_INTERNAL_SECRET` は `wrangler.jsonc` に **1 つも宣言が無い** |

**動かない経路を移植して「移行済み」と言わないため**である。必要になった時点で
`route.cljc` に足し、テストと binding を伴って戻す。これは「どちらの request path が
サービスか」という積年の問いに対する答えでもある —— quickstart §11 の問い (2) は、
**deploy されていた方を正本にする**ことで決着した。

## `kotoba/` は TypeScript のまま残した（測って残した）

この repo の TypeScript を全部消すのは移行ではなく破壊である。`kotoba/` は決済の
台帳実装（settlement rail catalog + E2E の payment / transaction / balance）で、
**appview ではない**。残す条件を 3 つとも測った:

| 条件 | 実測 |
|---|---|
| どの bundle にも入っていないか | 入っていない。deploy されていた `_worker.js` に `settlementRail` / `HARAI_DID_PREFIX` / `harai-kotoba` / `encryptedWrite` / `registerRail` は**すべて 0 件** |
| 移行が置き換えるものから参照されているか | されていない。`grep -rn kotoba appview/` は **0 件** |
| 依存が解決するか | する。pin された 2 つの commit は git で取得でき（`type=commit`）、redirect 先（`kotoba-lang/sdk` / `sdk-mock`）に実在する |

したがって**触っていない**。7 ファイルすべての sha256 と**ファイル数 7** を検証器に
固定してあるので、黙って増えることも変わることもできない。

⚠ **ただし今日この環境ではテストを走らせられない。** `npm install` が npm 11.16.0 で
`EALLOWSCRIPTS` を出して git 依存の `prepare` を拒否する。`npx npm@11.17.0` でも同じで、
理由は外側の npm ではなく**内側が system の npm を呼ぶ**ためである（実測、quickstart §2）。
これは npm 側の事情であって repo の欠陥ではないが、「7 tests all passing」は今日この
機械では再現していない —— 再現したのは**依存 commit が解決すること**までである。

## いま在るもの — 27 ファイル

| 面 | ファイル |
|---|---|
| 判断・描画・edge | `src/harai/{route.cljc, view.cljc, worker.cljs}` |
| テスト | `test/harai/route_test.cljk`（6 tests / 28 assertions） |
| ビルド | `deps.edn` / `shadow-cljs.edn` / `.gitignore` |
| Worker 設定 | `appview/harai-mcp-component/wrangler.jsonc` |
| actor 記述子 | `appview/harai-mcp-component/kotodama.jsonld` |
| 台帳（TypeScript、上記のとおり据え置き） | `kotoba/`（7 ファイル） |
| プロセス定義 | `bpmn/harai-control.bpmn` |
| 検査 | `scripts/{verify-harai-surface,mutate-harai-surface,smoke-worker}.cljs` |
| 文書 | `README.md` / `README.edn` / `MIGRATION-TODO.md` / `NOTICE` / `migration.edn` / `docs/operator-quickstart.md` / `docs/adr/0001-*.edn` |

**appview の TypeScript は 3 本 → 0 本、`.cljs`/`.cljc` が 0 本 → 3 本**（+ テスト 1 本）。
`kotoba/` の TypeScript 5 本は据え置き（合計 `.ts` は 8 → 5）。この 3 つの数はすべて
検証器の assertion なので、TS が appview に戻れば落ちる —— 撤去したパスに戻る場合
（`second-request-path-is-gone`）も、別名で入る場合（`no-typescript-in-the-appview`）も、
別々の assertion が捕まえる。

## ページが出す値・出さない値

env の**キー名**は出す。**値を出すのは 2 つだけ**:

- `AGENTGATEWAY_MCP_ROUTER_URL` —— どこへ中継するかは運用者が見る必要がある
- `APP_CAPABILITIES` —— 宣言メソッドの一覧。**8 という数をコードに焼かないため**、
  env から読んで描く

smoke はこれを**独立した印 3 つ**で見る: 表示しない var に置いた sentinel が出ていない
こと、中継先の値が出ていること、そして env に渡した**実在しないメソッド名**
（`probeMethodZeta`）が出ていること。最後の 1 つは「本物の 8 個を焼いていない」ことの
証拠で、同時に `closeAccount` が出ていないことも見る。片方だけの印では
「全部隠す」実装も「全部出す」実装も通ってしまう。

## UI

基盤は `kotoba-lang/jp-go-digital-design-system`（デジタル庁デザインシステム）。
色・寸法は `--hig-*` トークン契約だけで書き、raw hex も px フォントサイズも置かない。
app 固有 CSS は 3 行。CSS は外部リクエストゼロの方針どおり `shadow.resource/inline`
で bundle に焼く。

決定論的 audit（`kotoba-lang/design-quality`）で **100.00 / 100**（gate 95）。
**ただしこの点数が言えることは限られている** —— CLI 自身が「axes scored: 10 …
NOT scored: input-zoom, contrast」と出力する（`--extra-axes` で 12 になり、それでも
100.00 だった）。デザインシステムを完全に外したページでも 96.63 で PASS することが
別 repo で実測されている。**「CSS が実際に入っている」と言えるのは smoke の方**である:

| 探す文字列 | 何の主張か |
|---|---|
| `class="dads-table"` | view がライブラリの component を呼んだ |
| `--color-primitive-blue` | stylesheet が実際に bundle に入った |

前者だけでは落ちない —— それは view が出力する markup であって、CSS が 1 バイトも
入っていないページにも現れるからである（別 repo の実測: css 込み 74 / css 無し 6）。

## SvelteKit 用の設定も外した（測ってから）

`wrangler.jsonc` の `compatibility_flags`（`nodejs_compat` / `nodejs_als`）は
adapter-cloudflare が要求していたもので、cljs の `:esm` bundle には要らない。
**憶測では消さず、`wrangler dev --local`（wrangler 4.69.0、workerd）で
flags あり / 無しの両方を起動し、8 経路すべてが同じ応答であることを確かめてから
外した**（quickstart §6.1）。同じく消えた SvelteKit client を指していた `assets`
ブロックも撤去した。検証器が両方の不在を pin する。

`rules` の `CompiledWasm` は**残してある** —— この移行の対象ではなく、消す根拠を
測っていないからである（`.wasm` は 1 つも無いので不活性）。

## 呼び先が 1 つも解決しない（移行では直らない）

| ホスト | 役割 | DNS（2026-08-18 実測） |
|---|---|---|
| `harcom.etzhayyim.ai` | actor DID / wrangler route | **解決しない**（`etzhayyim.ai` に NS が無い） |
| `harai.etzhayyim.com` | 意図されていた名前（quickstart §9） | **解決しない** |
| `r3k9mwvx.etzhayyim.com` | nanoid 側の公開ホスト | **解決しない** |
| `mcp.etzhayyim.com` | `/xrpc/:nsid` の中継先 | **解決しない** |
| `dispatcher.etzhayyim.com` | facade の proxy 先（移していない） | **解決しない** |

deploy 先も中継先も、いま存在しない。`/xrpc/` は到達できなければ **502 を返す**
——成功と同じ形で隠さない。

## まだ決まっていないこと（移行は答えていない）

1. **どちらのメソッド語彙が本物か** —— 宣言された 8 個（kotodama / wrangler / BPMN の
   3 面が一致）か、`kotoba/src` が export する 11 個か。重なりは **3 個**だけで、
   `createPayment` / `settlePayment` を含む 5 個はどこにも実装が無い。
2. **actor が何という名前か** —— `harai.etzhayyim.com` が evidenced intent だが、
   `HARAI_DID_PREFIX` はいまも `harcom.etzhayyim.ai` の下で rail DID を発行している。

どちらも `scripts/verify-harai-surface.cljk` が現状を pin しており、直せば赤くなる。

## 検証

```bash
nbb scripts/verify-harai-surface.cljk          # 15 assertions
nbb scripts/mutate-harai-surface.cljk          # 21 demonstrations（検査器を落として確かめる）
nbb scripts/smoke-worker.cljk dist/worker.js   # ビルド済み bundle を実際に叩く
```

exit 0 = 全一致 / 1 = 食い違い / **2 = 判定できなかった**（0 と区別する）。
テスト・ビルド・採点の手順は `docs/operator-quickstart.md`。
