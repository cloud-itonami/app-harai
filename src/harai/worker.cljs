(ns harai.worker
  "Cloudflare Worker の入口。**この repo で唯一 Request/Response に触る層。**

  ここには判断を置かない —— どのハンドラが答えるかは `harai.route/dispatch` が
  決め、ページの中身は `harai.view` が組む。どちらも `.cljc` なので、ブラウザも
  ビルドも無しにテストできる。

  wrangler.jsonc の `main` は `../../dist/worker.js` を指し、それはこの名前空間を
  コンパイルしたものである。移行前は SvelteKit のビルド出力
  （`svelte/.svelte-kit/cloudflare/_worker.js`）を指していて、読み手が開く
  `src/app.ts` はどの bundle にも入っていなかった（ADR-0001、実測は
  docs/operator-quickstart.md §3）。

  `aget` を使うのは `:advanced-optimization` 下で env のキーが潰れないため
  （先例 `listingops.edge.worker` と同じ約束）。"
  (:require [harai.route :as route]
            [harai.view :as view]
            [shadow.resource :as rc]
            [clojure.string :as str]))

(def ^:private dds-css
  "DADS の CSS はビルド時に bundle へ焼く。外部リクエストゼロが design system の
  方針で、Worker から resource を読む経路も無い。"
  (rc/inline "jp_go_dds/dds.css"))

(defn- ->response [body {:keys [status content-type cache extra]}]
  (js/Response.
   body
   #js {:status status
        :headers (clj->js (merge {"content-type" content-type
                                  "cache-control" (or cache "no-store")}
                                 extra))}))

(defn- json [body status]
  (->response (js/JSON.stringify (clj->js body))
              {:status status :content-type "application/json; charset=utf-8"}))

(defn- env->map
  "env の **キーだけ** を keyword で拾う。値をまとめて外に出さない。"
  [env]
  (if env
    (into {} (map (fn [k] [(keyword k) (aget env k)])) (js/Object.keys env))
    {}))

(defn- cors-headers []
  {"access-control-allow-origin" "*"
   "access-control-allow-methods" "POST,OPTIONS"
   "access-control-allow-headers" "content-type,authorization"
   "access-control-max-age" "86400"})

(defn- forward-headers
  "呼び手の header をそのまま上流へ渡す（`host` だけ落とす）。

  移行前の `+server.ts` がこうしていた —— `new Headers(event.request.headers)`
  から `host` を消し、`content-type` と 2 本の `x-etzhayyim-*` を立てて MCP
  router へ POST する。**`authorization` を落とさないことが load-bearing** で、
  CORS の allow-headers にも `authorization` が入っている。ここで固定の header
  集合に置き換えると、呼び手の認証が router に届かなくなる —— それは移行では
  なく機能の削除である。

  `x-etzhayyim-bff` の値だけは変えた（`sveltekit-edge-bff` → `cljs-esm-worker`）。
  この header は「どの BFF が中継したか」を名乗るものなので、SvelteKit を名乗り
  続ける方が嘘になる。wrangler の `APP_FRAMEWORK` と同じ文字列にしてある。"
  [req nsid]
  (let [h (js/Headers. (.-headers req))]
    (.delete h "host")
    (.set h "content-type" "application/json")
    (.set h "x-etzhayyim-bff" "cljs-esm-worker")
    (.set h "x-etzhayyim-xrpc-method" nsid)
    h))

(defn- proxy-xrpc
  "XRPC を MCP router へ中継する。移行前に deploy されていた SvelteKit route と
  同じ形 —— jsonrpc の封筒（`tools/call`）に包み、`result` / `structuredContent`
  を剥がして返す。壊れた body は `{}` として通す（移行前の `.catch(() => ({}))`
  と同じ。拒否するのは方針変更であり、移行ではない —— quickstart §3 の実測）。"
  [req env nsid]
  (let [url (route/mcp-router-url (env->map env))
        headers (forward-headers req nsid)]
    (-> (.json req)
        (.catch (fn [_] #js {}))
        (.then
         (fn [input]
           (js/fetch url
                     #js {:method "POST"
                          :headers headers
                          :body (js/JSON.stringify
                                 #js {:jsonrpc "2.0"
                                      :id (.randomUUID js/crypto)
                                      :method "tools/call"
                                      :params #js {:name nsid :arguments input}})})))
        (.then (fn [resp]
                 (-> (.text resp)
                     (.then (fn [text]
                              (let [payload (try (when (seq text) (js/JSON.parse text))
                                                 (catch :default _ text))
                                    clj-payload (js->clj payload :keywordize-keys true)]
                                (if-not (.-ok resp)
                                  (json {:error "MCP router request failed"
                                         :upstream clj-payload}
                                        (.-status resp))
                                  (let [{:keys [ok? value error upstream]} (route/unwrap-mcp clj-payload)]
                                    (if ok?
                                      (json (or value {}) 200)
                                      (json {:error error :upstream upstream} 502))))))))))
        (.catch (fn [e]
                  ;; 到達できなかったことを 200 で隠さない。移行時点で
                  ;; mcp.etzhayyim.com は A レコードを返さないので、これは
                  ;; 想像上の経路ではなく今日の既定の結末である。
                  (json {:error "MCP router unreachable"
                         :detail (str (.-message e))
                         :url url}
                        502))))))

(defn- page-response [env]
  (let [e (env->map env)]
    (->response
     (view/render {:css dds-css
                   :routes route/routes
                   :vars (sort (keys e))
                   :mcp-url (route/mcp-router-url e)
                   :methods (route/declared-methods (:APP_CAPABILITIES e))})
     {:status 200
      :content-type "text/html; charset=utf-8"
      :cache "public, max-age=60"})))

(defn fetch-handler [req env _ctx]
  (let [url (js/URL. (.-url req))
        path (.-pathname url)
        {:keys [action nsid allow reason]} (route/dispatch (.-method req) path)]
    (case action
      :page   (page-response env)
      :xrpc   (proxy-xrpc req env nsid)
      :cors-preflight (->response nil {:status 204 :content-type "text/plain"
                                       :extra (cors-headers)})
      :bad-request (json {:error reason} 400)
      :method-not-allowed (->response (js/JSON.stringify #js {:error "Method Not Allowed"})
                                      {:status 405
                                       :content-type "application/json; charset=utf-8"
                                       :extra {"allow" allow}})
      (json {:error "Not Found"
             :routes (mapv (fn [r] (str (str/upper-case (name (:route/method r)))
                                        " " (:route/path r)))
                           route/routes)}
            404))))

(def handler #js {:fetch fetch-handler})
