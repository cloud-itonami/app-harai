# Operator quickstart — app-harai

**払い / harai — 決済・清算（payment & settlement clearing）。** 27 ファイル、
2 つの層でできている:

| 層 | 何か | 動くか |
|---|---|---|
| `src/harai/` | appview の Worker（ClojureScript）。`/` と `/xrpc/:nsid` を答える | **yes** —— ビルドして実際に叩いた（§6） |
| `kotoba/` | 台帳: 平文の settlement-rail catalog と E2E の payment / transaction / balance | **今日この機械では走らない** —— npm が git 依存を用意できない（§2） |

**2026-08-18 に appview は TypeScript/Svelte から ClojureScript へ移行した**
（`docs/adr/0001`）。移行前この repo には TypeScript の appview が 2 つあり、
**読み手が開くファイルと deploy されるファイルが別だった**。その状態は
2026-08-16 版のこの文書が測って記録し、いま `docs/adr/0001` が保存している。
本文書はその後の状態を記述する。

✅ の付いた手順はこの tree に対して 2026-08-18 に実行した。出力が引用されている
ところは、実際の出力である。

| 使ったもの | 版 |
|---|---|
| git | 2.51.0 |
| node | v26.3.0 |
| npm | 11.16.0 |
| nbb | v1.4.208 |
| java（ビルド時のみ） | openjdk 24.0.2 |

---

## 1. 取得して、書いてあることが本当か検査する ✅

```bash
git clone git@github.com:cloud-itonami/app-harai.git
cd app-harai
npx --yes nbb scripts/verify-harai-surface.cljk
```

実際の出力（末尾）:

```
held 15 / changed 0 / skipped 0 of 15
```

15 の assertion は 2 種類ある。**移行が閉じたこと**（deploy される entry が
src/ からビルドした bundle であること、撤去した 9 パスが戻っていないこと、
`kotoba/` が 7 ファイルのまま 1 バイトも変わっていないこと、ページが引数から
描かれること…）と、**移行が touch していない食い違い**（メソッド語彙、zone の
外に出た route、届かない firehose 購読、解決しない DID）。

後者は**直せば赤くなる**。それが意図である —— assertion と修正を同じ commit に
入れることで、正本を選ぶことが drift ではなく記録された決定になる。

`--no-net` で DNS を使う 1 件を飛ばせる。飛ばした件は **held ではなく skipped**
として別に数える。exit は 3 値: `0` 全一致 / `1` 動いた / **`2` 判定できなかった**
（入力が無い、assertion が 15 未満）。

## 2. 台帳（`kotoba/`）—— 今日は走らない ⚠

```bash
cd kotoba && npm install
```

実際の出力:

```
npm error code EALLOWSCRIPTS
npm error --allow-scripts is not allowed in project-scoped installs.
npm error git dep preparation failed
```

`npx --yes npm@11.17.0 install` でも**同じ**である（`npx npm@11.17.0 --version`
→ `11.17.0` を確認済み）。理由は外側の npm ではない —— エラー本文が名指しする
コマンドが `/opt/homebrew/lib/node_modules/npm/bin/npm-cli.js`、つまり **git 依存の
準備は system の npm（11.16.0）が実行する**からである。`package.json` に
`allowScripts` フィールドを足しても同じところで落ちる（試した）。

**これは npm 側の事情であって repo の欠陥ではない。** 2026-08-16 版のこの文書は
「11.17.0 なら 7 tests 通る」と書いていたが、**今日この機械では再現しない**。
再現したのは依存が実在することまでである:

```bash
git fetch https://github.com/etzhayyim/com-etzhayyim-sdk.git 12314a0cc5ac2feb49dd9789d5c002398acb6988
git cat-file -t 12314a0cc5ac2feb49dd9789d5c002398acb6988      # -> commit
git fetch https://github.com/etzhayyim/com-etzhayyim-sdk-mock.git c857ff9be5310bf433bfe1e8d3c0f677e213d667
git cat-file -t c857ff9be5310bf433bfe1e8d3c0f677e213d667      # -> commit
```

どちらも `commit` として取れる（`gh api` ではなく git に訊く —— この workspace では
存在する commit に対して API が 404 を返す事例が観測されている）。両依存の URL は
`etzhayyim/*` から `kotoba-lang/sdk` / `sdk-mock` へ redirect しており、**この repo は
その redirect に黙って依存している**。

**移行はこの層を触っていない。** appview ではないからである。判断は 3 つの実測に
よる（§3 の bundle 実測と、`grep -rn kotoba appview/` が 0 件、上記の commit 解決）。
7 ファイルの sha256 とファイル数は検証器に固定してある。

## 3. 移行前に deploy されていたのは何だったか ✅

移行の対象を『読んで』決めない。SvelteKit を実際にビルドして測った:

```bash
cp -R appview/harai-mcp-component/svelte /tmp/oracle && cd /tmp/oracle
npm install && npm run build          # vite 6.4.2 → built in 15.45s
grep -o 'id: "[^"]*"' .svelte-kit/output/server/manifest.js | sort -u
grep -c health .svelte-kit/cloudflare/_worker.js
for s in settlementRail HARAI_DID_PREFIX harai-kotoba encryptedWrite registerRail; do
  printf '%s %s\n' "$s" "$(grep -c $s .svelte-kit/cloudflare/_worker.js)"; done
```

実際の出力:

```
id: "/"
id: "/xrpc/[...path]"
0                       # health
settlementRail 0
HARAI_DID_PREFIX 0
harai-kotoba 0
encryptedWrite 0
registerRail 0
```

読み方は 3 つ:

1. **deploy 面の route はちょうど 2 本**だった。移行はこの 2 本を移した。
2. **`/health` は deploy 面に無かった。** 持っていたのは request の届かない
   `src/app.ts` の方である。移行で生やすのは移行ではないので、生やしていない。
3. **`kotoba/` はどの bundle にも入っていなかった。** だから残した。

## 4. テストを走らせる（ビルド不要・ブラウザ不要）✅

判断（`route.cljc`）と描画（`view.cljc`）は純 `.cljc` なので、nbb だけで回る。

```bash
K=~/github/com-junkawasaki/orgs/kotoba-lang
CP="src:test:$K/jp-go-digital-design-system/src:$K/html/src:$K/css/src"
cat > /tmp/run.cljs <<'EOF'
(require '[cljs.test :refer [run-tests]] 'harai.route-test)
(run-tests 'harai.route-test)
EOF
npx --yes nbb --classpath "$CP" /tmp/run.cljs
```

実際の出力:

```
Testing harai.route-test

Ran 6 tests containing 28 assertions.
0 failures, 0 errors.
```

何を固定しているか: `/xrpc/` は**空の nsid だけ** 400（`/xrpc/a/b` も
`com.example.someoneElse.doThing` も移行前と同じく中継する）、`/health` は **404**
（deploy 面に無かった）、MCP router の URL 解決、`result` / `structuredContent` の
剥がし方、そして**ページが route 表と env から描かれること**（固定値を焼いていたら
落ちる）。

## 5. ページを描画して採点する ✅

```bash
K=~/github/com-junkawasaki/orgs/kotoba-lang
CP="src:$K/jp-go-digital-design-system/src:$K/html/src:$K/css/src"
# view/render に css・route 表・env 相当を渡して 1 枚出す（下記は要点のみ）
cd $K/design-quality && npx --yes nbb -m design-quality.cli score /tmp/page.html --min 95
```

実際の出力（末尾）:

```
  100.00  /tmp/page.html
aggregate: 100.00
axes scored: 10 (viewport, safe-area, dynamic-viewport, tap-targets, focus-visible,
                 reduced-motion, overflow-guard, color-scheme, responsive, semantics)
NOT scored: input-zoom, contrast — pass --extra-axes to include the optional ones
gate: aggregate 100.00 >= min 95.00 -> PASS
```

`--extra-axes` を付けると **12 軸で 100.00**（同じく PASS）。

**この点数が言えることは限られている。** CLI 自身が「適用したのは 12 軸中 10 軸」
「適用しなかった軸について pass は何も言わない」と書いている。デザインシステムを
完全に外したページでも 96.63 で PASS することが別 repo で実測されている。
**「CSS が実際に入っている」と言えるのは §6 の smoke の方**である。

## 6. bundle をビルドして、実際に叩く ✅

**高負荷ビルドは同時 1 本に制限されている**（superproject `CLAUDE.md` の
resource governor）。直接叩かず、必ず guard 経由で:

```bash
node ~/github/com-junkawasaki/scripts/resource-guard.mjs run build -- \
  npx --yes shadow-cljs release worker
```

lock を他セッションが持っていると **exit 2 で拒否される。迂回しない** ——
`resource-guard: build is already running (pid=…)` はエラーではなく順番待ちで
ある（この walk では 9 回待った）。実際の出力（末尾）:

```
[:worker] Build completed. (55 files, 12 compiled, 0 warnings, 7.18s)
```

`dist/worker.js` は 246,462 バイト。次に、**その bundle を import して叩く**:

```bash
npx --yes nbb scripts/smoke-worker.cljk dist/worker.js
```

21 項目すべて PASS（末尾）:

```
OK	the built bundle answers as the route table says
```

見ているもの: default export が `fetch` を持つ / `GET /` が 200 の HTML で route 表の
path を載せている / env のキーは出て**表示対象でない値は出ない** / **中継先の値は出る**
/ **`APP_CAPABILITIES` から読んだ値が出る**（env に実在しないメソッド名を渡して確認）
/ DADS の component が呼ばれている / **stylesheet が実際に bundle に入っている** /
`POST /xrpc/` が 400 / `OPTIONS` が 204 / 未知パスが 404 / 誤 method が 405 /
**`/health` が 404** / 多段パスが 400 にならず単段と同じ結末になる / 到達不能な中継が
**502**（200 で隠さない）。

多段パスの検査は `.invalid` の中継先（RFC 2606 で必ず解決しない TLD）に対して
行う —— 実 DNS に依存させないためで、`mcp.etzhayyim.com` がいま NXDOMAIN である
ことに寄りかからない。

**これがこの repo で唯一 deploy される成果物に触る検査である。** bundle が無ければ
**exit 2**（0 とも 1 とも別）で「判定できなかった」と言う。

### 6.1 workerd で実際に動かし、SvelteKit 用の compatibility_flags を外した ✅

Node で bundle を import する smoke より強い証拠がある —— **実際の Workers
ランタイムで動かす**ことである。deploy はしない（`wrangler dev --local` だけ）。

```bash
cd appview/harai-mcp-component
wrangler dev --local --port 8931 --ip 127.0.0.1     # wrangler 4.69.0
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8931/
```

`compatibility_flags`（`nodejs_compat` / `nodejs_als`）は SvelteKit の
adapter-cloudflare が要求していたものである。cljs の `:esm` bundle には要らない
**はず**だが、**憶測で消さずに両方で測った**:

| リクエスト | flags あり | flags 無し |
|---|---|---|
| `GET /` | 200（HTML、DDS の CSS 45 件・`dads-table` 1 件） | **同じ** |
| `POST /xrpc/` | 400 `{"error":"Missing XRPC method"}` | **同じ** |
| `OPTIONS /xrpc/x` | 204 | **同じ** |
| `GET /nope` | 404（route 表を載せる） | **同じ** |
| `GET /xrpc/x` | 405 | **同じ** |
| `GET /health` | 404 | **同じ** |
| `POST /xrpc/com.etzhayyim.apps.harai.listPayments` | 502 `MCP router unreachable` | **同じ** |
| `POST /xrpc/a/b`（多段） | 502（単段と同じ結末） | **同じ** |

**全 8 経路が同一だったので flags を外した。** workerd のログに出る 2 件の
`Uncaught Error: internal error` は、解決しないホストへの fetch そのもので、
Worker はそれを捕まえて 502 にしている（応答を見れば分かる）。

検証器はこの撤去を pin する（`no-sveltekit-compat-flags`）。戻ってくれば落ちる。


## 7. 検査器を落として確かめる ✅

```bash
npx --yes nbb scripts/mutate-harai-surface.cljk
```

```
21 / 21 demonstrations passed
```

control 1（無改変のコピーは静か）+ floor 2（入力を消すと exit 2 / `--no-net` は
skipped と数える）+ mutation 18。**各 mutation は exit 1 であることに加え、
動いた assertion の id が期待と完全一致すること**まで見る —— exit code だけなら、
無関係な理由で赤くなった検査器も合格してしまう。

複数の id が動くのが正しい mutation もある（`kotodama.jsonld` を編集すると、
そこに書かれた事実と custody の hash の両方が動く）。それは 2 つと書いてある。
実際、`src/app.ts` を戻す mutation は最初 1 つだけを期待していて **FAIL した** ——
app.ts は「撤去したパス」であると同時に「appview の TypeScript」でもあるからで、
**間違っていたのは期待の方だった**。

gate 側も 1 つずつ落として確かめた（**mutation は 1 つずつ当てる**。2 つ同時だと
互いを隠す）:

| 壊したもの | 赤くなったもの |
|---|---|
| `/xrpc/a/b` を 400 に絞る | route-test の当該 assertion のみ |
| ページから `viewport` meta を外す | design-quality **88.76 < 95 FAIL**（finding が viewport を名指し） |
| `route/dispatch` を存在しない var に改名 | **ビルドが落ちる**（`:shadow.build.compiler/warning-as-error true`） |
| `(rc/inline "jp_go_dds/dds.css")` → `""` | smoke の「stylesheet が入った」だけ（「component を呼んだ」は緑のまま） |
| env の値をページに出す | smoke の sentinel だけ |
| 中継先をページから消す | smoke の「中継先を出している」だけ |
| `dist/` を消す | smoke が **exit 2** |

復元は **git の index から**行い、`/tmp` に backup ファイルを置いていない ——
この機械では並行する複数の agent が `/tmp` を共有しており、他人の backup を復元して
別 repo の namespace を取り込んだ事故が同じ日に起きている。最後に復元して build し直した
bundle の sha256 は、変異前の値と**バイト一致**した
（`d039e7b456346d8f21d6342f69da9dbda809c38af45aecb2c84d2873b54aeffa`、246,462 バイト）。

⚠ **index を backup に使うなら、変異が当たっている間に `git add` してはならない。**
実際にやってしまった: 値漏洩の変異が当たっているときにファイル数を数えるつもりで
`git add -A` を走らせ、**漏らす版を index に焼いた**。その後の
`git checkout -- <file>` は「復元」に見えて漏洩を戻し、次の変異（中継先を隠す）と
二重になった。結果、**「中継先を出している」が緑のままだった** —— 漏れた値の中に
中継先 URL が含まれていたからである。brief が警告していた masking をそのまま踏んだ。
index を直して**中継先を隠す変異だけ**を当て直したところ、その 1 件だけが赤くなった。

## 8. 宣言されたメソッドと実装が食い違っている（移行では直らない）✅

3 つの宣言面 —— `kotodama.jsonld` の `profile.capabilities`、`wrangler.jsonc` の
`APP_CAPABILITIES`、`bpmn/harai-control.bpmn` の 8 つの `serviceTask` —— は**完全に
一致する**。8 つの名前:

```
closeAccount createPayment getBalance listPayments
listTransactions refundPayment settlePayment transferFunds
```

`kotoba/src/index.ts` は 11 の関数を export する。重なりは **3**:

| | |
|---|---|
| 宣言され**かつ**実装されている (3) | `getBalance` `listPayments` `listTransactions` |
| 宣言され、**実装がどこにも無い** (5) | `closeAccount` `createPayment` `refundPayment` `settlePayment` `transferFunds` |
| 実装され、**宣言に無い** (8) | `coverage` `getPayment` `getRail` `listRails` `recordPayment` `recordTransaction` `registerRail` `setBalance` |

**移行前は宣言面が 4 つあった。** 4 つ目は facade の `/health` が返すメソッド一覧で、
facade ごと撤去したので 3 つになった。数が減ったのは合意が壊れたからではない。

命名は 2 つの半分が別のモデルに対して設計されたことを示唆する（`createPayment` 対
`recordPayment`、宣言が一度も言及しない rail catalog）。どちらが本物かは tree の
中では決まらない。**これは移行が答えていない問いである。**

## 9. actor のホスト名は codemod の産物である（移行では直らない）✅

この repo の identity はすべて `harcom.etzhayyim.ai` を指す ——
`kotodama.jsonld` の `@id`、`kotoba/src/types.ts` の `HARAI_DID_PREFIX`
（settlement rail ごとに DID を発行する）、`wrangler.jsonc` の route。
**このドメインは存在しない。** `etzhayyim.ai` には NS が無い。

```
harcom.etzhayyim.ai       A=(なし)  NS=(なし)
harai.etzhayyim.com       A=(なし)  NS=(なし)
r3k9mwvx.etzhayyim.com    A=(なし)  NS=(なし)
mcp.etzhayyim.com         A=(なし)  NS=(なし)
dispatcher.etzhayyim.com  A=(なし)  NS=(なし)
etzhayyim.com             A=172.67.179.128 104.21.51.111
```

`etzhayyim.ai` → `etzhayyim.com` の書き換えが、app 自身の名前の中の `ai.` を
置換してしまった形である（`harai.etzhayyim.ai` → `har**com**.etzhayyim.ai`）。
兄弟 repo `cloud-itonami/shiharai` が同じ規則で同じ壊れ方をしており、そちらは
`did:web:shiharai.etzhayyim.com` を保っているので、**意図された名前はほぼ確実に
`harai.etzhayyim.com`** である。zone の外に出た route 宣言もこれで説明がつく:

```jsonc
{ "pattern": "harcom.etzhayyim.ai/*", "zone_name": "etzhayyim.com" }
```

route pattern のホスト名は zone の内側でなければならない。意図された名前なら
`harai.etzhayyim.com/*` で整合する。**ただし `harai.etzhayyim.com` も今日は
解決しない**ので、文字列を直すのは必要だが十分ではない。

## 10. firehose 購読は発火し得ない（移行では直らない）✅

`kotodama.jsonld` は 2 つの collection を購読する:

```
com.etzhayyim.apps.harai.payment
com.etzhayyim.apps.harai.transaction
```

どちらも collection として書かれることが無い。`registry.ts` は payment /
transaction / balance を `encryptedWrite` で書き、これらの NSID は封筒の**内側**の
`innerType` である（外側の collection は SDK 既定の
`com.etzhayyim.encrypted.record`）。購読が照合するのは外側なので、この 2 つは
永遠に一致しない。逆に外側の collection として実際に書かれる
`com.etzhayyim.apps.harai.settlementRail` は**購読されていない**。

これは E2E 設計が意図どおり働いた結果であって、その中のバグではない。ただし
宣言された trigger は不活性であり、payment イベントを待つ購読者は永遠に待つ。

## 11. 決まっていないこと / やっていないこと

**この移行が答えていない問い**（tree の中に根拠が無いか、別の決定に属する）:

1. **どちらのメソッド語彙が本物か**（§8）。
2. **actor が何という名前か**（§9）。`HARAI_DID_PREFIX` はいまも壊れた名前の下で
   rail DID を発行している。
3. **firehose の購読をどう直すか**（§10）。

**やっていないこと**（省略を pass と混ぜないため）:

- **deploy していない**（`wrangler deploy` は実行していない）。
- `wrangler dev --local`（workerd）で動かしていない。したがって
  `compatibility_flags`（`nodejs_compat` / `nodejs_als`）は **残してある** ——
  SvelteKit の adapter-cloudflare が要求していたもので cljs の :esm bundle には
  要らないはずだが、**実測していない設定変更はしない**。
- `kotoba/` の 7 テストは §2 の理由で走らせていない。

## 12. 成熟度計器が見ているもの

```
· orgs/cloud-itonami/app-harai  own=0.049  axis-docs=0bp → +2500bp
```

移行前、README の項が 0 だったのは計器が `README.md` を読み、この repo が
`README.edn`（`:canonical-metadata :edn`）を宣言していたためである。**移行で
`README.md` を足したので、この項は次回から変わる**（`README.edn` は
1 バイトも変えずに残してある）。`axis-substrate` が 0 なのは top-level の `src/`
を数えるためで、これも移行で `src/harai/` ができたので変わる。どちらも測り方の
都合であって発見ではない（ADR-2608052000 は `uncounted/*` として別に報告する）。
