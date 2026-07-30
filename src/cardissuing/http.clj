(ns cardissuing.http
  "The governed HTTP surfaces a consent surface hands proposals to.

  Same two-listener shape as cloud-itonami-esim, for the same reason:

    consent  (default :1341)  POST /commit                  X-CARD-CONSENT-TOKEN
                              GET  /proposals/<reference>   X-CARD-CONSENT-TOKEN
                              GET  /healthz                 (open)
    operator (default :1342)  POST /proposals/<ref>/decide  X-CARD-OPERATOR-TOKEN

  BOTH surfaces now require a token, and they are DIFFERENT tokens. Holding the app's
  consent token does not let the app decide its own proposals; that is the whole point of
  the two gates, and a shared secret would have quietly merged them.

  A pending proposal awaits THIS actor's operator. If decide sat on the consent
  surface, the consent surface could approve its own proposals -- it would hold both
  gates, and the whole containment would be a comment. Different listeners means the
  consent surface cannot reach decide because it is not listening there. The
  operator surface requires CARD_OPERATOR_TOKEN and refuses every decide when that
  is unset, because failing open would make it most dangerous exactly when nobody
  had configured it.

  WHAT IS DIFFERENT HERE FROM THE eSIM ACTOR: this one can actually issue a card.

  So approving is not the same event as issuing, and this surface reports them
  separately:

    {\"status\": \"committed\", \"record\": {...}}      approved AND actuated
    {\"status\": \"approved-not-actuated\", ...}       approval RECORDED, provider
                                                    refused or is not configured
    {\"status\": \"held\", \"refusal\": {...}}          governor refused (HARD)
    {\"status\": \"pending\", \"reference\": \"…\"}      awaiting this actor's operator

  `approved-not-actuated` is the state that matters and the one a two-state answer
  would destroy. The operator's approval is recorded in the ledger the moment they
  give it; whether Stripe then accepted the call is a separate fact that can fail on
  its own. Collapsing them would either lose an approval that really happened or
  claim a card exists when the provider said no.

  The idempotency key is DERIVED FROM THE REFERENCE, deterministically, so a
  retried decide cannot issue a second card. That is the property
  kotoba.card.actuation requires a caller to supply and the reason it refuses to
  generate one itself.

  The actuator is INJECTED and defaults to absent. With no actuator configured every
  approval answers `approved-not-actuated` with :no-actuator-configured -- which is
  honest about a deployment that has approved something it cannot carry out, rather
  than silently reporting success."
  (:require [clojure.data.json :as json]
            [langgraph.graph :as g]
            [kotoba.card.actuation :as actuation]
            [cardissuing.operation :as operation]
            [cardissuing.store :as store])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io ByteArrayOutputStream]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(def ^:private max-body-bytes 65536)

(def actor-context
  "Phase 3 is stated rather than defaulted, because it decides which ops reach an
  operator at all: :card/issue, :card/lifecycle, :bin/sponsor,
  :authorization/decide and :dispute/initiate are absent from every phase's `:auto`
  set (permanently -- see cardissuing.phase), while :cardholder/intake auto-commits
  at 3 and therefore never carries an operator approval. Lowering this to 2 makes
  intake operator-gated but takes :card/issue out of `:writes` entirely."
  {:actor-id "cloud-itonami-card-issuing"
   :actor-role :issuer-ops
   :role :card-issuing-operator
   :phase 3})

(def operator-token-env "CARD_OPERATOR_TOKEN")

(def consent-token-env
  "The consent surface's shared secret with whichever app holds the Passkey ceremony.

  Loopback binding was the only thing guarding /commit, and loopback is not an
  authorisation: every process on the host shares it. Without this, anything running
  locally could POST a proposal carrying `\"status\": \"approved\"` and a
  passkey-credential-id it invented, and the only thing between that and a real card
  would be this actor's operator approving what they believed a human had consented to.

  The governor is unaffected either way -- it recomputes every ground-truth fact from
  recorded state -- so this does not gate what may be committed. It gates WHO MAY CLAIM
  a subject consented, which is the one thing a governor cannot recompute."
  "CARD_CONSENT_TOKEN")

(def consent-token-header "X-CARD-CONSENT-TOKEN")

(defn consent-token
  "The configured consent token, or nil when unset. Read at call time so a test can
  redef it and a deployment can rotate without a restart."
  []
  (let [t (System/getenv consent-token-env)]
    (when (and t (seq t)) t)))

;; ---------------------------------------------------------------------------
;; wire helpers
;; ---------------------------------------------------------------------------

(defn- read-body [^HttpExchange exchange]
  (with-open [in (.getRequestBody exchange)
              out (ByteArrayOutputStream.)]
    (let [buf (byte-array 8192)]
      (loop [total 0]
        (let [n (.read in buf)]
          (cond
            (neg? n) (.toString out StandardCharsets/UTF_8)
            (> (+ total n) max-body-bytes)
            (throw (ex-info "request body too large" {:type :http/body-too-large}))
            :else (do (.write out buf 0 n) (recur (+ total n)))))))))

(defn- send! [^HttpExchange exchange status payload]
  (let [bytes (.getBytes (json/write-str payload) StandardCharsets/UTF_8)]
    (doto (.getResponseHeaders exchange)
      (.set "Content-Type" "application/json; charset=utf-8")
      (.set "Cache-Control" "no-store"))
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)] (.write out bytes))
    (.close exchange)))

(defn- kw
  "Read a keyword the wire sent as a string, keeping its namespace. nil for
  anything unusable, so an unrecognised op reaches the governor's op allowlist as
  nil and is refused there rather than coerced into something plausible."
  [v]
  (cond
    (keyword? v) v
    (and (string? v) (seq v)) (keyword v)
    :else nil))

(defn- keywordize-value [value]
  (cond-> (or value {})
    (contains? value :event) (update :event kw)
    (contains? value :state) (update :state kw)
    (contains? value :decision) (update :decision kw)))

(defn proposal->request
  "Map a consent surface's proposal onto this actor's own request.

  The value is passed through but NOT trusted: cardissuing.governor recomputes
  every ground-truth fact from this actor's own recorded state."
  [{:keys [op value]}]
  (let [value (keywordize-value value)]
    {:op (kw op)
     :subject (or (:cardholder-id value) (:card-reference value))
     :value value}))

;; ---------------------------------------------------------------------------
;; actuation
;; ---------------------------------------------------------------------------

(defn idempotency-key-for
  "Deterministic from the reference, so a retried decide cannot issue a second
  card. kotoba.card.actuation deliberately refuses to generate a key itself --
  only a caller knows whether a call is a retry, and here the reference IS that
  knowledge."
  [reference]
  (str "cardissuing-" reference))

(defn actuate!
  "Carry out an approved proposal against the live issuer.

  Takes the op and value from the actor's REQUEST, not from the commit record's
  `:payload`. The payload is the store mutation (`{:id .. :status ..}`) and carries
  neither the op nor the fields the provider needs -- reading the op from it yields
  nil for every op, which would make every actuation quietly answer
  `:op-has-no-live-counterpart`.

  Returns {:actuated? true :record m} or {:actuated? false :refusal m}. Never
  throws: a provider failure is a fact to record, and the approval that preceded it
  has already happened."
  [actuator op value reference by]
  (if (nil? actuator)
    {:actuated? false
     :refusal {:rule :no-actuator-configured
               :detail (str "承認は記録されましたが、実行する provider が設定されて"
                            "いません（この deployment はカードを発行できません）")}}
    (let [approval {:by by :reference reference}
          idem (idempotency-key-for reference)
          out (try
                (case op
                  :card/issue
                  ;; The provider needs STRIPE's cardholder id (ich_…), which is not
                  ;; this actor's own cardholder id (ch-1). Substituting one for the
                  ;; other would create a card against a cardholder that does not
                  ;; exist, or worse, against someone else's. So it is refused when
                  ;; unknown rather than guessed -- see README `The cardholder gap`.
                  (if-let [ich (:stripe-cardholder-id value)]
                    (actuation/issue-card! actuator ich value approval idem)
                    {:card/ok? false
                     :card/refusal
                     {:rule :stripe-cardholder-unknown
                      :detail (str "この cardholder の Stripe cardholder id "
                                   "(:stripe-cardholder-id) が不明です。"
                                   "承認された cardholder/intake で先に作成してください")}})

                  :card/lifecycle
                  (actuation/set-card-state! actuator (:card-reference value)
                                             (:event value) approval idem)

                  :cardholder/intake
                  (actuation/create-cardholder! actuator value approval idem)

                  ;; Everything else is a record-keeping op with no live
                  ;; counterpart -- a BIN sponsorship or a dispute is not something
                  ;; this provider can perform, and saying so beats pretending.
                  {:card/ok? false
                   :card/refusal {:rule :op-has-no-live-counterpart :op op}})
                (catch Exception e
                  {:card/ok? false
                   :card/refusal {:rule :actuator-threw :detail (str (.getMessage e))}}))]
      (if (true? (:card/ok? out))
        {:actuated? true :record (dissoc out :card/ok?)}
        {:actuated? false :refusal (:card/refusal out)}))))

;; ---------------------------------------------------------------------------
;; wire mapping
;; ---------------------------------------------------------------------------

(defn- disposition->wire
  "Map a graph state onto the wire vocabulary. One function so the commit path and
  the status path cannot drift into describing the same disposition differently."
  [state thread]
  (let [verdict (:verdict state)]
    (case (:disposition state)
      :commit
      {:status "committed"
       :record (merge {:reference thread} (:payload (:record state)))}

      :escalate
      {:status "pending"
       :reference thread
       :reason (str (or (some-> (last (:audit state)) :reason) :actuation))
       :detail (str "この操作は " (:actor-id actor-context)
                    " 自身の operator 承認を必要とします（consent surface の "
                    "Passkey 同意は operator 承認ではありません）")}

      {:status "held"
       :refusal {:rule (or (some-> (:violations verdict) first :rule) :held)
                 :violations (mapv :rule (:violations verdict))
                 :confidence (:confidence verdict)}})))

(defn commit-outcome
  "Run one proposal through the actor.

  A fresh thread id per call, deliberately: langgraph continues an existing
  thread's state when a checkpoint exists for that id, so keying on the proposal id
  would let a caller-supplied :approval carry from one POST into the next."
  [app proposal]
  (let [request (proposal->request proposal)
        thread (str "commit-" (or (:id proposal) "anon") "-" (random-uuid))
        state (g/invoke app {:request request :context actor-context}
                        {:thread-id thread})
        wire (disposition->wire state thread)]
    ;; A :commit HERE is an AUTO-commit: this path never carries an operator
    ;; approval, so nothing was actuated. `kotoba.card.actuation/precheck` would
    ;; refuse the call anyway (no named approver), and it is better to say the
    ;; provider was not called than to let "committed" read as "the card exists".
    ;; At the actor's phase 3, :cardholder/intake is the one op that lands here.
    (cond-> wire
      (= "committed" (:status wire))
      (assoc :actuation "not-attempted"
             :actuation-reason "auto-committed-without-operator-approval"))))

(defn proposal-status
  "What became of one reference. \"unknown\" for a reference this process never saw,
  including every one from before a restart -- the checkpointer is in memory, and
  reporting an unknown reference as still pending would be a guess dressed as a
  fact."
  [app reference]
  (if-let [checkpoint (g/get-state app reference)]
    (assoc (disposition->wire (:state checkpoint) reference)
           :resolved? (not= :interrupted (:status checkpoint)))
    {:status "unknown"
     :reference reference
     :detail "この reference は本プロセスに記録がありません（再起動で失われた可能性）"}))

(defn operator-decide!
  "Resume a pending proposal with the OPERATOR's decision, and -- on approval --
  carry it out.

  The only path that resumes the graph's approval interrupt, reachable only from the
  operator listener. `by` is required and recorded: an approval nobody is named for
  cannot be audited.

  On approval the graph commits FIRST (so the approval is in the ledger regardless
  of what the provider does) and the provider is called SECOND. If the provider
  refuses, the answer is `approved-not-actuated` -- the approval really happened and
  must not be discarded because a downstream call failed."
  [app actuator reference {:keys [status by]}]
  (cond
    (not (contains? #{"approved" "rejected"} status))
    {:status "held"
     :refusal {:rule :malformed-decision
               :detail "status は approved か rejected でなければなりません"}}

    (or (nil? by) (and (string? by) (empty? by)))
    {:status "held"
     :refusal {:rule :approver-unnamed
               :detail "by（承認者）は必須です — 名前のない承認は監査できません"}}

    (nil? (g/get-state app reference))
    {:status "unknown" :reference reference
     :detail "この reference は本プロセスに記録がありません"}

    :else
    (let [state (g/invoke app {:approval {:status (keyword status) :by by}}
                          {:thread-id reference :resume? true})
          wire (assoc (disposition->wire state reference) :decided-by by)]
      (if-not (= "committed" (:status wire))
        wire
        (let [op (get-in state [:request :op])
              value (get-in state [:request :value])
              {:keys [actuated? record refusal]} (actuate! actuator op value reference by)]
          (if actuated?
            (assoc wire :record (merge (:record wire) {:provider record}))
            ;; The approval stands. Only the actuation did not happen.
            (assoc wire
                   :status "approved-not-actuated"
                   :approval-recorded true
                   :refusal refusal)))))))

;; ---------------------------------------------------------------------------
;; listeners
;; ---------------------------------------------------------------------------

(defn handler
  "The CONSENT surface: commit and read, never decide."
  [store app]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (let [method (.getRequestMethod ^HttpExchange exchange)
              path (.getPath (.getRequestURI ^HttpExchange exchange))
              expected (consent-token)
              presented (.getFirst (.getRequestHeaders ^HttpExchange exchange)
                                   consent-token-header)]
          (cond
            ;; /healthz stays open: it carries no subject data and a deployment needs to
            ;; be able to ask whether this actor is up before it has a token to ask with.
            (and (= "GET" method) (= "/healthz" path))
            (send! exchange 200 {:status "ok"
                                 :actor (:actor-id actor-context)
                                 :cardholders (count (store/all-cardholders store))})

            ;; Everything past /healthz needs the token, and an UNSET token refuses
            ;; rather than waves through -- failing open would leave this surface most
            ;; permissive exactly where nobody had configured it, which is the same
            ;; reasoning the operator surface already applies.
            (nil? expected)
            (send! exchange 503
                   {:status "held"
                    :refusal {:rule :consent-surface-unconfigured
                              :detail (str consent-token-env
                                           " が未設定のため proposal を受け付けません")}})

            (not= expected presented)
            (send! exchange 401
                   {:status "held" :refusal {:rule :consent-token-mismatch}})

            (and (= "GET" method) (re-matches #"/proposals/[^/]+" path))
            (send! exchange 200
                   (proposal-status app (subs path (count "/proposals/"))))

            (and (= "POST" method) (= "/commit" path))
            (let [body (json/read-str (read-body exchange) :key-fn keyword)
                  proposal (:proposal body)]
              (if-not (map? proposal)
                (send! exchange 400 {:status "held"
                                     :refusal {:rule :malformed-request
                                               :detail "proposal がありません"}})
                (send! exchange 200 (commit-outcome app proposal))))

            :else
            (send! exchange 404 {:status "held"
                                 :refusal {:rule :not-found :detail path}})))
        (catch Exception e
          (send! exchange 500 {:status "held"
                               :refusal {:rule :actor-error
                                         :detail (str (.getMessage e))}}))))))

(defn- operator-token [] (let [t (System/getenv operator-token-env)]
                           (when (and t (seq t)) t)))

(defn operator-handler
  "The OPERATOR surface: decide, and nothing else.

  Refuses everything when CARD_OPERATOR_TOKEN is unset. An unauthenticated decide
  endpoint here can issue a real payment card."
  [app actuator]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (let [method (.getRequestMethod ^HttpExchange exchange)
              path (.getPath (.getRequestURI ^HttpExchange exchange))
              expected (operator-token)
              presented (.getFirst (.getRequestHeaders ^HttpExchange exchange)
                                   "X-CARD-OPERATOR-TOKEN")]
          (cond
            (nil? expected)
            (send! exchange 503
                   {:status "held"
                    :refusal {:rule :operator-surface-unconfigured
                              :detail (str operator-token-env
                                           " が未設定のため decide を受け付けません")}})

            (not= expected presented)
            (send! exchange 401
                   {:status "held" :refusal {:rule :operator-token-mismatch}})

            (and (= "POST" method) (re-matches #"/proposals/[^/]+/decide" path))
            (let [reference (second (re-matches #"/proposals/([^/]+)/decide" path))
                  body (json/read-str (read-body exchange) :key-fn keyword)]
              (send! exchange 200 (operator-decide! app actuator reference body)))

            :else
            (send! exchange 404 {:status "held"
                                 :refusal {:rule :not-found :detail path}})))
        (catch Exception e
          (send! exchange 500 {:status "held"
                               :refusal {:rule :actor-error
                                         :detail (str (.getMessage e))}}))))))

(defn- listener [port ^HttpHandler h]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)]
    ;; Loopback only: neither surface has transport security of its own, and this
    ;; one can issue a payment card.
    (.createContext server "/" h)
    (.setExecutor server nil)
    (.start server)
    server))

(defn start!
  "Start both surfaces. Returns {:consent :operator :store :app :actuator}.

  The two listeners share one compiled graph -- and therefore one checkpointer --
  which is what lets the operator resume a thread the consent surface created. They
  do NOT share a port, which is what stops the consent surface resuming it itself.

  `:actuator` defaults to nil: a deployment must inject one deliberately. With none,
  every approval answers approved-not-actuated, which is honest about a deployment
  that can approve but not issue."
  ([] (start! {}))
  ([{:keys [port operator-port store actuator]
     :or {port 1341 operator-port 1342}}]
   (let [st (or store (store/seed-db))
         app (operation/build st)]
     {:store st
      :app app
      :actuator actuator
      :consent (listener port (handler st app))
      :operator (listener operator-port (operator-handler app actuator))})))

(defn stop! [{:keys [consent operator]}]
  (when consent (.stop ^HttpServer consent 0))
  (when operator (.stop ^HttpServer operator 0)))

(defn -main [& args]
  (let [port (if-let [p (first args)] (parse-long p) 1341)
        operator-port (if-let [p (second args)] (parse-long p) (inc port))
        ;; A live actuator is opt-in via env, and test mode is the default inside
        ;; the provider. Nothing here reads or holds a key.
        actuator (when (System/getenv "CARD_ACTUATOR")
                   (require 'io.stripe.issuing.core)
                   ((resolve 'io.stripe.issuing.core/provider)
                    :mode (if (= "live" (System/getenv "CARD_ACTUATOR_MODE"))
                            :live :test)))
        running (start! {:port port :operator-port operator-port :actuator actuator})]
    (println "cloud-itonami-card-issuing")
    (println (str "  consent  http://127.0.0.1:" port))
    (println "    POST /commit                 -- a consented proposal")
    (println "    GET  /proposals/<reference>  -- what became of one")
    (println "    GET  /healthz")
    (println (str "  operator http://127.0.0.1:" operator-port))
    (println "    POST /proposals/<reference>/decide")
    (println (str "         requires header X-CARD-OPERATOR-TOKEN = $" operator-token-env))
    (println)
    (if (consent-token)
      (println (str "consent surface: token configured (" consent-token-header ")"))
      (println (str "consent surface: " consent-token-env
                    " is UNSET, so every proposal is refused (fail closed)")))
    (if (operator-token)
      (println "operator surface: token configured")
      (println (str "operator surface: " operator-token-env
                    " is UNSET, so every decide is refused (fail closed)")))
    (if actuator
      (println (str "actuator: stripe-issuing, mode "
                    (if (= "live" (System/getenv "CARD_ACTUATOR_MODE")) "LIVE" "test")))
      (println "actuator: none. Approvals will answer approved-not-actuated."))
    (println)
    (println "Approving and issuing are different events and are reported")
    (println "separately: approved-not-actuated means the approval is in the ledger")
    (println "and the provider did not carry it out.")
    (.join (Thread/currentThread))
    running))
