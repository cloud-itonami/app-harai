#!/usr/bin/env nbb
;; verify-harai-surface — pin what this repository currently says about its own
;; HTTP surface and its own method vocabulary.
;;
;; WHY THIS EXISTS. app-harai describes its service in six places that do not
;; agree with each other: kotodama.jsonld, wrangler.jsonc, bpmn/harai-control.bpmn,
;; appview/*/src/app.ts, the SvelteKit route under appview/*/svelte/src/routes/, and
;; kotoba/src/. Reading any one of them gives a confident and wrong answer about
;; what is deployed. This checker writes the disagreements down as assertions so
;; they cannot drift further without someone noticing.
;;
;; IT DOES NOT PICK A WINNER. Which surface is canonical is an owner decision
;; (see docs/operator-quickstart.md §6). Every assertion below states the state as
;; it is on the commit this file landed with. **Fixing any of them turns this
;; checker red — that is the intent, not a bug.** When you fix one, update the
;; assertion in the same commit and say what you chose.
;;
;;   nbb scripts/verify-harai-surface.cljs            # from the repo root
;;   nbb scripts/verify-harai-surface.cljs --no-net   # skip the DNS check
;;
;; Exit codes are three-valued on purpose (ADR-2608136000): a check that could
;; not run must not be indistinguishable from a check that ran and was happy.
;;   0 = every assertion held
;;   1 = an assertion changed  (the report names which id, and what it now sees)
;;   2 = could not answer      (an input was missing, or nothing was scanned)

(ns verify-harai-surface
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:child_process" :as cp]))

(def argv (vec (drop 2 (js->clj js/process.argv))))
(def no-net? (some #{"--no-net"} argv))

(def APPVIEW "appview/harai-mcp-component")

;; ── input floor ──────────────────────────────────────────────────────────────
;; Every path this checker reasons about. A missing input is exit 2, never a
;; pass: "the file is not there" and "the file is fine" must not look alike.

(def required-inputs
  [(str APPVIEW "/kotodama.jsonld")
   (str APPVIEW "/wrangler.jsonc")
   (str APPVIEW "/src/app.ts")
   (str APPVIEW "/svelte/src/routes/xrpc/[...path]/+server.ts")
   "bpmn/harai-control.bpmn"
   "kotoba/src/index.ts"
   "kotoba/src/types.ts"
   "kotoba/src/registry.ts"])

(defn slurp* [p]
  (when (fs/existsSync p) (str (fs/readFileSync p "utf8"))))

(defn strip-jsonc
  "wrangler.jsonc carries // comments. Drop them before JSON.parse, but never
   inside a string literal — the APP_DESCRIPTION value contains a URL-ish em dash
   today and could contain // tomorrow."
  [s]
  (str/join "\n"
            (for [line (str/split-lines s)]
              (loop [i 0 in-str? false esc? false]
                (cond
                  (>= i (count line)) line
                  esc? (recur (inc i) in-str? false)
                  (= \\ (nth line i)) (recur (inc i) in-str? true)
                  (= \" (nth line i)) (recur (inc i) (not in-str?) false)
                  (and (not in-str?) (= "//" (subs line i (min (count line) (+ i 2)))))
                  (subs line 0 i)
                  :else (recur (inc i) in-str? false))))))

;; ── assertions ───────────────────────────────────────────────────────────────
;; Each returns {:id :status :saw :want :note}. :status is one of
;; :held / :changed / :skipped. :skipped is reported separately from :held and
;; never counts toward the pass total.

(defn held [id want saw note] {:id id :status :held :want want :saw saw :note note})
(defn changed [id want saw note] {:id id :status :changed :want want :saw saw :note note})
(defn check [id want saw note] (if (= want saw) (held id want saw note) (changed id want saw note)))

(defn declared-methods [src]
  (let [j (js->clj (js/JSON.parse src) :keywordize-keys true)]
    (set (get-in j [:profile :capabilities]))))

(defn wrangler-methods [src]
  (let [j (js->clj (js/JSON.parse (strip-jsonc src)) :keywordize-keys true)]
    (set (js->clj (js/JSON.parse (get-in j [:vars :APP_CAPABILITIES]))))))

(defn bpmn-methods [src]
  (set (map second (re-seq #"taskDefinition type=\"com\.etzhayyim\.apps\.harai\.([A-Za-z]+)\"" src))))

(defn facade-methods [src]
  ;; the /health response lists the methods the facade claims to speak
  (when-let [block (second (re-find #"methods:\s*\[([\s\S]*?)\]" src))]
    (set (map second (re-seq #"\"([a-zA-Z]+)\"" block)))))

(defn implemented-methods [src]
  (when-let [block (second (re-find #"export \{([\s\S]*?)\} from" src))]
    (into #{} (remove str/blank?) (map str/trim (str/split block #",")))))

(defn dns-resolves?
  "Return true/false, or :unknown when the lookup itself could not be performed.
   An unreachable resolver must not be reported as a resolving host, and must not
   be reported as a missing one either."
  [host]
  (try
    (let [out (str (cp/execSync (str "host -W 5 " host " 2>&1 || true")
                                #js {:encoding "utf8"}))]
      (cond
        (re-find #"NXDOMAIN" out) false
        (re-find #"has address|has IPv6|is an alias" out) true
        :else :unknown))
    (catch :default _ :unknown)))

(defn run-checks []
  (let [kotodama (slurp* (str APPVIEW "/kotodama.jsonld"))
        wrangler (slurp* (str APPVIEW "/wrangler.jsonc"))
        appts    (slurp* (str APPVIEW "/src/app.ts"))
        route    (slurp* (str APPVIEW "/svelte/src/routes/xrpc/[...path]/+server.ts"))
        bpmn     (slurp* "bpmn/harai-control.bpmn")
        index    (slurp* "kotoba/src/index.ts")
        types    (slurp* "kotoba/src/types.ts")
        registry (slurp* "kotoba/src/registry.ts")
        wj       (js->clj (js/JSON.parse (strip-jsonc wrangler)) :keywordize-keys true)
        kj       (js->clj (js/JSON.parse kotodama) :keywordize-keys true)

        declared (declared-methods kotodama)
        wcaps    (wrangler-methods wrangler)
        bmeth    (bpmn-methods bpmn)
        fmeth    (facade-methods appts)
        impl     (implemented-methods index)
        overlap  (into (sorted-set) (filter impl declared))

        ;; the svelte routes that actually get built into the deployed worker
        route-dir (str APPVIEW "/svelte/src/routes")
        svelte-routes (when (fs/existsSync route-dir)
                        (str (cp/execSync (str "find '" route-dir "' -name '+server.ts' -o -name '+page.svelte'")
                                          #js {:encoding "utf8"})))

        ;; "@id" is not a readable keyword, so read it off the raw text rather
        ;; than through the keywordized map.
        actor-did (second (re-find #"\"@id\"\s*:\s*\"([^\"]+)\"" kotodama))
        did-host  (some-> actor-did (str/replace #"^did:web:" "") (str/split #":") first)
        wr-routes (:routes wj)
        bad-routes (vec (for [r wr-routes
                              :let [pat (:pattern r) zone (:zone_name r)
                                    hostname (first (str/split pat #"/"))]
                              :when (not (or (= hostname zone) (str/ends-with? hostname (str "." zone))))]
                          {:pattern pat :zone zone}))

        subscribed (set (get-in kj [:triggers :subscribeRepos :collections]))
        inner-types (into #{} (map second)
                          (re-seq #"export const \w+_INNER_TYPE = \"([^\"]+)\"" types))
        outer-colls (into #{} (map second)
                          (re-seq #"export const RAIL_COLLECTION = \"([^\"]+)\"" types))]
    (cond-> []

      true (conj (check :deployed-entry-is-sveltekit-not-app-ts
                        "svelte/.svelte-kit/cloudflare/_worker.js"
                        (:main wj)
                        (str "wrangler main decides what runs. " APPVIEW "/src/app.ts is "
                             (count (str/split-lines appts)) " lines that no request reaches.")))

      true (conj (check :health-served-only-by-undeployed-facade
                        {:facade true :deployed false}
                        {:facade (boolean (re-find #"/health" appts))
                         :deployed (boolean (and svelte-routes (re-find #"health" svelte-routes)))}
                        "a health check against the deployed worker hits SvelteKit's 404 (not_found_handling: none)"))

      true (conj (check :malformed-body-rejected-only-by-undeployed-facade
                        {:facade-400 true :deployed-swallows true}
                        {:facade-400 (boolean (re-find #"InvalidJson" appts))
                         :deployed-swallows (boolean (re-find #"\.catch\(\(\)\s*=>\s*\(\{\}\)\)" route))}
                        "deployed route turns a corrupt body into a tool call with empty arguments"))

      true (conj (check :nsid-prefix-enforced-only-by-undeployed-facade
                        {:facade-prefixed true :deployed-any-nsid true}
                        {:facade-prefixed (boolean (re-find #"NSID_PREFIX" appts))
                         :deployed-any-nsid (boolean (re-find #"event\.params\.path" route))}
                        "deployed route forwards any nsid the caller names to the MCP router"))

      true (conj (check :four-declaration-surfaces-agree
                        true
                        (= declared wcaps bmeth fmeth)
                        (str "kotodama.jsonld / wrangler APP_CAPABILITIES / bpmn serviceTasks / app.ts "
                             "all name the same " (count declared) " methods")))

      true (conj (check :declared-vs-implemented-overlap
                        #{"getBalance" "listPayments" "listTransactions"}
                        (set overlap)
                        (str (count declared) " declared, " (count impl) " implemented in kotoba/src, "
                             (count overlap) " in common; "
                             (count (remove impl declared)) " declared methods have no implementation "
                             "and " (count (remove declared impl)) " implemented functions are undeclared")))

      true (conj (check :wrangler-route-outside-its-declared-zone
                        [{:pattern "harcom.etzhayyim.ai/*" :zone "etzhayyim.com"}]
                        bad-routes
                        "a route pattern's hostname must sit inside zone_name; Cloudflare cannot attach this one"))

      true (conj (check :subscribed-collections-are-inner-types-only
                        {:subscribed-that-are-inner-only 2 :outer-collections-subscribed 0}
                        {:subscribed-that-are-inner-only (count (filter inner-types subscribed))
                         :outer-collections-subscribed (count (filter subscribed outer-colls))}
                        (str "registry.ts writes those NSIDs as innerType inside com.etzhayyim.encrypted.record, "
                             "so the firehose never sees them as a collection; "
                             (str/join "," outer-colls) " is the one outer collection and it is not subscribed")))

      true (conj (if (or no-net? (= :unknown (dns-resolves? did-host)))
                   {:id :actor-did-domain-does-not-resolve :status :skipped
                    :want false :saw :unknown
                    :note (if no-net? "--no-net" "DNS lookup could not be performed — NOT counted as a pass")}
                   (check :actor-did-domain-does-not-resolve
                          false
                          (dns-resolves? did-host)
                          (str actor-did " cannot be resolved while " did-host
                               " has no DNS; kotoba/src/types.ts mints rail DIDs under it")))))))

;; ── report ───────────────────────────────────────────────────────────────────

(defn -main []
  (let [missing (remove fs/existsSync required-inputs)]
    (when (seq missing)
      (println "SCANNED\t0\tharai-surface")
      (println "Refusing to report a pass: these inputs are missing:")
      (doseq [m missing] (println "  MISSING" m))
      (js/process.exit 2)))
  (let [results (try (run-checks)
                     (catch :default e
                       (println "SCANNED\t0\tharai-surface")
                       (println "Refusing to report a pass:" (.-message e))
                       (js/process.exit 2)))
        held-n    (count (filter #(= :held (:status %)) results))
        changed   (filter #(= :changed (:status %)) results)
        skipped   (filter #(= :skipped (:status %)) results)]
    (println (str "SCANNED\t" (count results) "\tharai-surface"))
    (doseq [r results]
      (println (str ({:held "  held    " :changed "  CHANGED " :skipped "  skipped "} (:status r))
                    (name (:id r))))
      (when (= :changed (:status r))
        (println (str "      want: " (pr-str (:want r))))
        (println (str "      saw : " (pr-str (:saw r)))))
      (println (str "      " (:note r))))
    (println)
    (println (str "held " held-n " / changed " (count changed) " / skipped " (count skipped)
                  " of " (count results)))
    ;; An evidence floor. If the assertion list is ever emptied or filtered down
    ;; to nothing, "0 changed" must not read as clean.
    (when (< (count results) 9)
      (println (str "Refusing to report a pass: expected at least 9 assertions, ran " (count results)))
      (js/process.exit 2))
    (when (seq changed)
      (println "One or more pinned facts moved. If you fixed it, update the assertion here in the same commit.")
      (js/process.exit 1))
    (js/process.exit 0)))

(-main)
