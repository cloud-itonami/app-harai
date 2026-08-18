(ns harai.route-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [harai.route :as route]
            [harai.view :as view]))

(deftest dispatch-page
  (is (= :page (:action (route/dispatch "GET" "/"))))
  (is (= :method-not-allowed (:action (route/dispatch "POST" "/"))))
  (testing "移行前に deploy されていた面は route が 2 本だけだった。/health は無い"
    (is (= :not-found (:action (route/dispatch "GET" "/health"))))
    (is (= :not-found (:action (route/dispatch "GET" "/nope"))))))

(deftest dispatch-xrpc
  (testing "nsid はそのまま tool 名になる"
    (is (= {:action :xrpc :nsid "com.etzhayyim.apps.harai.listPayments"}
           (route/dispatch "POST" "/xrpc/com.etzhayyim.apps.harai.listPayments"))))
  (testing "空だけが 400。多段は移行前の [...path] と同じく転送する（絞るのは方針変更）"
    (is (= :bad-request (:action (route/dispatch "POST" "/xrpc/"))))
    (is (= {:action :xrpc :nsid "a/b"} (route/dispatch "POST" "/xrpc/a/b"))))
  (testing "移行前と同じく、宣言外の nsid も中継する（allow-list は deploy されていなかった）"
    (is (= {:action :xrpc :nsid "com.example.someoneElse.doThing"}
           (route/dispatch "POST" "/xrpc/com.example.someoneElse.doThing"))))
  (testing "preflight と method"
    (is (= :cors-preflight (:action (route/dispatch "OPTIONS" "/xrpc/x"))))
    (is (= :method-not-allowed (:action (route/dispatch "GET" "/xrpc/x"))))))

(deftest mcp-url-resolution
  (is (= "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"
         (route/mcp-router-url {})))
  (is (= "https://a.example/x" (route/mcp-router-url {:AGENTGATEWAY_MCP_ROUTER_URL "https://a.example/x/"})))
  (testing "空白だけの設定は未設定として扱う"
    (is (= "https://b.example" (route/mcp-router-url {:AGENTGATEWAY_MCP_ROUTER_URL "   "
                                                     :MCP_ROUTER_URL "https://b.example"})))))

(deftest unwrap
  (is (= {:ok? true :value {:a 1}} (route/unwrap-mcp {:result {:structuredContent {:a 1}}})))
  (is (= {:ok? true :value {:a 1}} (route/unwrap-mcp {:result {:a 1}})))
  (is (false? (:ok? (route/unwrap-mcp {:error {:message "boom"}})))))

(deftest declared-methods-comes-from-env
  (testing "APP_CAPABILITIES（wrangler の値）から読む。数をコードに焼かない"
    (is (= ["createPayment" "listPayments"]
           (route/declared-methods "[\"createPayment\",\"listPayments\"]"))))
  (testing "読めないときは空。嘘の一覧を作らない"
    (is (= [] (route/declared-methods nil)))
    (is (= [] (route/declared-methods "")))))

(deftest page-shows-the-real-routes
  (testing "ページは route 表と env から描く。固定値を焼かない（ADR-0001 の欠陥）"
    (let [html (view/render {:css "/*x*/" :routes route/routes
                             :vars [:APP_NANOID :APP_UI_TYPE]
                             :mcp-url "https://mcp.example/x"
                             :methods (route/declared-methods
                                       "[\"createPayment\",\"getBalance\"]")})]
      (doseq [r route/routes]
        (is (str/includes? html (:route/path r))
            (str (:route/path r) " がページに出ていない")))
      (is (str/includes? html "APP_NANOID"))
      (is (str/includes? html "https://mcp.example/x"))
      (testing "宣言メソッドは env から来た 2 個が出る（8 を焼いていない）"
        (is (str/includes? html "createPayment"))
        (is (str/includes? html "getBalance"))
        (is (not (str/includes? html "closeAccount"))))
      (is (not (str/includes? html "No public route is declared"))))))
