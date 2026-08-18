(ns harai.view
  "この appview の説明ページ。純 hiccup。

  基盤は `jp-go-dds`（デジタル庁デザインシステム）—— superproject の skill
  `kotoba-uiux` が定める新規 UI の base。色・寸法は `--hig-*` トークン契約で
  書き、raw hex も px フォントサイズも置かない。

  **表示する事実はすべて引数で受け取る。ページの中に焼かない。** これは装飾の
  都合ではなく、移行前のページが持っていた欠陥そのものへの答えである ——
  `+page.svelte` は `routeCount: 0` / `routes: []` / `vars: []` を literal で
  持っており、同じディレクトリの wrangler.jsonc が route 2 本・var 8 個を宣言
  していることに気づけなかった。ここでは route 表も宣言メソッドも渡す側が持ち、
  ページは描くだけなので、両者がずれる余地が無い。"
  (:require [jp-go-dds.core :as dds]
            [jp-go-dds.page :as page]
            [jp-go-dds.tokens :as tokens]
            [clojure.string :as str]))

(def app-css
  "app 固有の最小 CSS。`--hig-*` 契約だけを使う（bridge が DADS の上に再定義する）。
  DADS を base にした app の下には `shitsuke.hig` が居ないので、bridge が運んで
  いないトークンは何にも解決しない —— 使うのは運ばれている中だけ。"
  (str/join
   "\n"
   [".harai-lede { color: var(--hig-color-secondary-label); max-width: 42rem; }"
    ".harai-note { color: var(--hig-color-secondary-label); font-size: var(--hig-text-footnote-font-size); }"
    ".harai-mono { font-family: var(--hig-font-mono); }"]))

(defn- route-rows [routes]
  (mapv (fn [r]
          [(str/upper-case (name (:route/method r)))
           [:span {:class "harai-mono"} (:route/path r)]
           (:route/doc r)])
        routes))

(defn body
  "opts:
   :routes    harai.route/routes（この Worker が実際に答えるもの）
   :vars      wrangler が渡した env のキー（**キー名だけ**。値は下の 2 つを除き出さない）
   :mcp-url   XRPC の中継先（route/mcp-router-url の戻り値。**値そのものを出す**）
   :methods   APP_CAPABILITIES が宣言するメソッド名（**env から読んだ値**）

  ここに `kotoba/src` の実装数のような『この repo についての事実』は渡さない。
  Worker は実行時にそれを測れないので、渡せば必ず焼いた定数になる —— それは
  このページが直した欠陥そのものである。実装との重なりは README と
  docs/operator-quickstart.md §4 が持つ。"
  [{:keys [routes vars mcp-url methods]}]
  (dds/container
   (dds/section
    {}
    (dds/heading 1 "harai — 払い / 決済・清算")
    [:p {:class "harai-lede"}
     "支払い・清算・返金・残高・送金・取引履歴を扱う appview の公開面。"
     "決済そのものはここには無く、この面は XRPC を AgentGateway の MCP router へ"
     "中継する。台帳の実装（settlement rail catalog と E2E 台帳）は同じ repo の "
     [:span {:class "harai-mono"} "kotoba/"] " にある。"])

   (dds/section
    {:title "この面が答えるもの"}
    (dds/table {:caption "公開ルート"
                :headers ["METHOD" "PATH" "何をするか"]
                :rows (route-rows routes)})
    [:p {:class "harai-note"}
     "この表は Worker の route 表そのものから描いている。ページに焼いた値では"
     "ないので、実際に答えるものと表示がずれない。"])

   (dds/section
    {:title "宣言されているメソッド"}
    (if (seq methods)
      [:div
       (into [:p] (interpose " " (map (fn [m] (dds/chip-label m)) methods)))
       [:p {:class "harai-note"}
        "これは wrangler の " [:span {:class "harai-mono"} "APP_CAPABILITIES"]
        " が宣言する " (str (count methods)) " 個を、env から読んで描いたもの。"
        "宣言と実装の重なりは README と docs/operator-quickstart.md §4 が持つ"
        "——ここでは測れないので描かない。"]]
      [:p {:class "harai-note"} "APP_CAPABILITIES が渡されていない（ローカル描画）。"]))

   (dds/section
    {:title "実行時の設定"}
    (if (seq vars)
      [:div
       (into [:p] (interpose " " (map (fn [k] (dds/chip-label (name k))) vars)))
       [:p {:class "harai-note"}
        "キー名のみ。**値を出しているのは 2 つだけ** —— 下の中継先と、上の "
        [:span {:class "harai-mono"} "APP_CAPABILITIES"]
        "。どこへ中継し何を名乗るかは運用者が見る必要があるので意図的に出して"
        "いる。それ以外の値は出さない。"]]
      [:p {:class "harai-note"} "env が渡されていない（ローカル描画）。"])
    [:p {:class "harai-note"} "XRPC の中継先: "
     [:span {:class "harai-mono"} mcp-url]])

   (dds/section
    {:title "現在地"}
    [:p {:class "harai-lede"}
     "この appview は TypeScript/Svelte から ClojureScript へ移行済み（ADR-0001）。"
     "deploy される bundle は、いま読んでいるソースからコンパイルされたものである。"]
    [:p {:class "harai-note"}
     "中継先も公開ホストも、いま DNS で解決しない。中継できなければ 502 を返す"
     "——成功と同じ形で隠さない。"])))

(defn render
  "完全な HTML 文書。`css` は呼び出し側が渡す（ライブラリは I/O を持たない）。"
  [{:keys [css] :as opts}]
  (page/->page
   {:title "harai — 払い / 決済・清算"
    :description "支払い・清算・返金・残高・送金・取引履歴を扱う appview の公開面。"
    :lang "ja"
    :css css
    :app-css (str tokens/bridge-css "\n" app-css)}
   (body opts)))
