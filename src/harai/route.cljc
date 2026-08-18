(ns harai.route
  "どのハンドラが答えるか —— データと純関数だけで決める。

  `.cljs` ではなく `.cljc` なのは意図的である。edge worker のうち検査する価値が
  あるのは経路の判断で、それはブラウザもビルドもネットワークも無しにここで
  試せる。`harai.worker` が Request/Response に触る唯一の名前空間であり、
  この file が既に決めたこと以外は何もしない。

  ingress capability が qualify した時（`:native-aot` / `:wasm-aot` はいまだ
  pending —— ADR-2606290000）、最初に `.kotoba` へ移るのもここである。route 表は
  スカラと文字列の上の判断であり、それは移行に耐える形そのものだからである。

  **ここに書いてあるのは移行前に deploy されていた面と同じ形である。**
  移行前の実体は SvelteKit のビルド出力（`svelte/.svelte-kit/cloudflare/_worker.js`）
  で、その route 表はちょうど 2 本（`/` と `/xrpc/[...path]`）だった。実測は
  docs/operator-quickstart.md §3 と ADR-0001。"
  (:require [clojure.string :as str]))

(def routes
  "公開面をデータとして持つ。ページはこの表を描くので、**答える route と
  ページが宣伝する route がずれ得ない** —— 移行前のページは `routeCount: 0` を
  literal で持ちながら、隣の wrangler.jsonc は route 2 本と var 8 個を宣言して
  いた（ADR-0001）。"
  [{:route/path "/"           :route/method :get  :route/kind :page
    :route/doc "この appview の説明ページ"}
   {:route/path "/xrpc/:nsid" :route/method :post :route/kind :proxy
    :route/doc "XRPC を AgentGateway の MCP router へ中継する"}
   {:route/path "/xrpc/:nsid" :route/method :options :route/kind :cors
    :route/doc "CORS preflight（204）"}])

(defn- xrpc-nsid
  "`/xrpc/<nsid>` の nsid。**空文字だけが nil**。

  多段パス（`/xrpc/a/b`）も通す。移行前の SvelteKit route は rest parameter
  `[...path]` で受けており、`a/b` をそのまま tool 名として転送していた（実測:
  docs/operator-quickstart.md §3、`FOREIGN nsid → HTTP 200 | tool:
  com.example.someoneElse.doThing`）。ここで 1 セグメントに絞ると挙動が変わる
  —— **それは移行ではなく方針変更**であり、移行の commit に紛れ込ませない。"
  [path]
  (when (str/starts-with? path "/xrpc/")
    (let [rest' (subs path (count "/xrpc/"))]
      (when (seq rest') rest'))))

(defn dispatch
  "method + path → 何をするか。Request も Response も知らない。

  返すのは `{:action …}` で、`:action` は
  `:page` / `:xrpc` / `:cors-preflight` / `:not-found` / `:method-not-allowed` /
  `:bad-request` のいずれか。

  **`/health` は無い。** 移行前に deploy されていた面にも無かった —— 持っていたのは
  どの request も届かない `src/app.ts` の方で、`grep -c health _worker.js` は 0
  だった（quickstart §3）。移行で新しく生やすのは移行ではないので、生やしていない。"
  [method path]
  (let [m (keyword (str/lower-case (or method "get")))
        p (or path "")]
    (cond
      (and (= m :options) (str/starts-with? p "/xrpc/"))
      {:action :cors-preflight}

      (str/starts-with? p "/xrpc/")
      (if (= m :post)
        (if-let [nsid (xrpc-nsid p)]
          {:action :xrpc :nsid nsid}
          {:action :bad-request :reason "Missing XRPC method"})
        {:action :method-not-allowed :allow "POST, OPTIONS"})

      (= p "/") (if (= m :get)
                  {:action :page}
                  {:action :method-not-allowed :allow "GET"})
      :else {:action :not-found})))

(defn mcp-router-url
  "env の設定 → MCP router の URL。末尾スラッシュは落とす。

  既定値をここに焼くのは、**どこへ行くのかを 1 箇所で読めるようにする**ため。
  移行前の `+server.ts` と同じ優先順（`AGENTGATEWAY_MCP_ROUTER_URL` →
  `MCP_ROUTER_URL` → 既定）で、空白だけの設定は未設定として扱う。"
  [{:keys [AGENTGATEWAY_MCP_ROUTER_URL MCP_ROUTER_URL]}]
  (let [pick (fn [s] (when (and (string? s) (seq (str/trim s))) (str/trim s)))]
    (-> (or (pick AGENTGATEWAY_MCP_ROUTER_URL)
            (pick MCP_ROUTER_URL)
            "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message")
        (str/replace #"/+$" ""))))

(defn unwrap-mcp
  "MCP router の応答から、呼び手に返す値を取り出す。

  `{:result {:structuredContent X}}` → X、`{:result X}` → X、それ以外は素通し。
  `{:error …}` は呼び出し側が 502 にするので、ここでは判定だけ返す。移行前の
  `+server.ts` と同じ剥がし方である。"
  [payload]
  (cond
    (and (map? payload) (contains? payload :error))
    {:ok? false :error (get-in payload [:error :message] "MCP router returned an error")
     :upstream payload}

    (and (map? payload) (contains? payload :result))
    (let [r (:result payload)]
      {:ok? true :value (if (and (map? r) (contains? r :structuredContent))
                          (:structuredContent r)
                          r)})

    :else {:ok? true :value payload}))

(defn declared-methods
  "wrangler の `APP_CAPABILITIES`（JSON 配列の文字列）→ メソッド名の vector。

  **ページはこれを env から読んで描く。** 8 という数を view に焼かないためで
  ある —— 焼けば、宣言が変わってもページは古い数を言い続ける（ADR-0001 が
  記録した `routeCount: 0` と同じ欠陥）。読めなければ空を返す。嘘の 8 を描くより
  何も描かない方がよい。

  JSON パーサを使わずに文字列を引くのは、`.cljc` の両側で同じ答えにするため。
  この値は wrangler が渡す JSON 配列（文字列の並び）だけなので、これで足りる。"
  [s]
  (if (string? s)
    (vec (map second (re-seq #"\"([^\"]+)\"" s)))
    []))
