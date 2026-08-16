#!/usr/bin/env nbb
;; mutate-harai-surface — check the checker.
;;
;; scripts/verify-harai-surface.cljs is only worth having if it can tell the
;; difference between the repository as it is and the repository changed. This
;; runs it against deliberately altered copies and requires, for each one:
;;
;;   * exit 1, and
;;   * the CHANGED ids are EXACTLY the ones the mutation should have moved.
;;
;; The second requirement is the one that matters. Exit code alone would accept a
;; checker that goes red for the wrong reason — and a mutation that breaks a
;; different assertion than intended looks, from the exit code, like a successful
;; demonstration (CLAUDE.md, ADR-2608136000).
;;
;; It also pins both floors: an untouched copy must exit 0, and a copy with an
;; input removed must exit 2 — not 0, and not 1.
;;
;;   nbb scripts/mutate-harai-surface.cljs        # from the repo root

(ns mutate-harai-surface
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as cp]))

(def APPVIEW "appview/harai-mcp-component")
(def KOTODAMA (str APPVIEW "/kotodama.jsonld"))
(def WRANGLER (str APPVIEW "/wrangler.jsonc"))
(def APPTS    (str APPVIEW "/src/app.ts"))
(def ROUTE    (str APPVIEW "/svelte/src/routes/xrpc/[...path]/+server.ts"))
(def INDEX    "kotoba/src/index.ts")

(defn sub!
  "Replace `from` with `to` in file `f` under root `r`. Fails loudly if `from` is
   not present — a mutation that silently changed nothing would make the checker
   look discriminating when it never saw anything different."
  [r f from to]
  (let [p (path/join r f)
        s (str (fs/readFileSync p "utf8"))]
    (when-not (str/includes? s from)
      (throw (js/Error. (str "mutation target absent in " f ": " (pr-str from)))))
    (fs/writeFileSync p (str/replace s from to))))

(def mutations
  ;; [label expected-changed-ids mutate-fn]
  [["wrangler main points at the facade"
    #{:deployed-entry-is-sveltekit-not-app-ts}
    (fn [r] (sub! r WRANGLER "\"main\": \"svelte/.svelte-kit/cloudflare/_worker.js\""
                  "\"main\": \"src/app.ts\""))]

   ["the facade stops serving /health"
    #{:health-served-only-by-undeployed-facade}
    (fn [r] (sub! r APPTS "url.pathname === \"/health\"" "url.pathname === \"/_nope\""))]

   ["a deployed route starts serving health"
    #{:health-served-only-by-undeployed-facade}
    (fn [r] (let [d (path/join r APPVIEW "svelte/src/routes/health")]
              (fs/mkdirSync d #js {:recursive true})
              (fs/writeFileSync (path/join d "+server.ts") "export const GET = () => new Response('ok');\n")))]

   ["the facade stops rejecting malformed bodies"
    #{:malformed-body-rejected-only-by-undeployed-facade}
    (fn [r] (sub! r APPTS "InvalidJson" "SomethingElse"))]

   ["the deployed route stops swallowing malformed bodies"
    #{:malformed-body-rejected-only-by-undeployed-facade}
    (fn [r] (sub! r ROUTE ".catch(() => ({}))" ".catch((e) => { throw e; })"))]

   ["the facade stops restricting the nsid prefix"
    #{:nsid-prefix-enforced-only-by-undeployed-facade}
    (fn [r] (sub! r APPTS "NSID_PREFIX" "ANY_NSID_AT_ALL"))]

   ["one declaration surface renames a method"
    ;; renames a method outside the 3-method overlap, so ONLY the agreement
    ;; assertion may move — if the overlap assertion also fires, the mutation
    ;; was not the surgical one this case claims to be
    #{:four-declaration-surfaces-agree}
    (fn [r] (sub! r KOTODAMA "\"closeAccount\"" "\"closeAccountV2\""))]

   ["the implementation grows a declared method"
    #{:declared-vs-implemented-overlap}
    (fn [r] (sub! r INDEX "  coverage,\n" "  coverage,\n  createPayment,\n"))]

   ["the stray route is moved into its zone"
    #{:wrangler-route-outside-its-declared-zone}
    (fn [r] (sub! r WRANGLER "\"pattern\": \"harcom.etzhayyim.ai/*\""
                  "\"pattern\": \"harcom.etzhayyim.com/*\""))]

   ["the firehose subscribes to the plaintext collection"
    #{:subscribed-collections-are-inner-types-only}
    (fn [r] (sub! r KOTODAMA "\"com.etzhayyim.apps.harai.transaction\""
                  "\"com.etzhayyim.apps.harai.settlementRail\""))]

   ["the actor DID moves to a domain that resolves"
    #{:actor-did-domain-does-not-resolve}
    (fn [r] (sub! r KOTODAMA "\"@id\": \"did:web:harcom.etzhayyim.ai\""
                  "\"@id\": \"did:web:etzhayyim.com\""))]])

(defn fresh-copy []
  (let [d (str (fs/mkdtempSync (path/join (os/tmpdir) "harai-mut-")))]
    (cp/execSync (str "git ls-files -z | xargs -0 tar cf - | (cd '" d "' && tar xf -)")
                 #js {:stdio "ignore"})
    d))

(defn run-verifier [dir & args]
  (let [cmd (str "cd '" dir "' && nbb scripts/verify-harai-surface.cljs " (str/join " " args) " 2>&1")
        res (try {:out (str (cp/execSync cmd #js {:encoding "utf8"})) :code 0}
                 (catch :default e
                   {:out (str (or (some-> (.-stdout e) str) "") (or (some-> (.-stderr e) str) ""))
                    :code (or (.-status e) 1)}))]
    (assoc res :changed
           (into #{} (map (comp keyword second))
                 (re-seq #"CHANGED\s+(\S+)" (:out res))))))

(defn -main []
  (let [results (atom [])
        record! (fn [label ok? detail] (swap! results conj {:label label :ok? ok? :detail detail}))]

    ;; floor 1 — an untouched copy is quiet. Without this the whole run could be
    ;; a checker that is simply always red.
    (let [d (fresh-copy) {:keys [code changed]} (run-verifier d)]
      (record! "control: unmutated copy" (and (= 0 code) (empty? changed))
               (str "exit " code ", changed " (pr-str changed)))
      (cp/execSync (str "rm -rf '" d "'")))

    ;; floor 2 — a missing input is exit 2, distinct from both pass and fail.
    (let [d (fresh-copy)]
      (fs/unlinkSync (path/join d "kotoba/src/types.ts"))
      (let [{:keys [code out]} (run-verifier d)]
        (record! "floor: a missing input exits 2" (= 2 code)
                 (str "exit " code (when (re-find #"MISSING" out) ", named the missing file"))))
      (cp/execSync (str "rm -rf '" d "'")))

    ;; floor 3 — the network check reports itself as skipped, never as held.
    (let [d (fresh-copy) {:keys [code out]} (run-verifier d "--no-net")]
      (record! "floor: --no-net marks the DNS check skipped, not held"
               (and (= 0 code)
                    (re-find #"skipped actor-did-domain-does-not-resolve" out)
                    (re-find #"skipped 1" out))
               (str "exit " code))
      (cp/execSync (str "rm -rf '" d "'")))

    ;; the mutations
    (doseq [[label expected mutate] mutations]
      (let [d (fresh-copy)]
        (try
          (mutate d)
          (let [{:keys [code changed]} (run-verifier d)]
            (record! label
                     (and (= 1 code) (= expected changed))
                     (str "exit " code ", changed " (pr-str changed)
                          (when (not= expected changed) (str " — expected " (pr-str expected))))))
          (catch :default e (record! label false (str "mutation failed to apply: " (.-message e))))
          (finally (cp/execSync (str "rm -rf '" d "'"))))))

    (let [rs @results
          bad (remove :ok? rs)]
      (doseq [{:keys [label ok? detail]} rs]
        (println (str (if ok? "  ok   " "  FAIL ") label))
        (println (str "         " detail)))
      (println)
      (println (str (- (count rs) (count bad)) " / " (count rs) " demonstrations passed"))
      ;; evidence floor: this harness is worthless if it silently runs nothing.
      (when (< (count rs) 14)
        (println (str "Refusing to report a pass: expected at least 14 demonstrations, ran " (count rs)))
        (js/process.exit 2))
      (js/process.exit (if (seq bad) 1 0)))))

(-main)
