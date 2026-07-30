(ns cardissuing.http-test
  "Wire behaviour over real sockets.

  The property this file exists for: APPROVING AND ISSUING ARE DIFFERENT EVENTS.
  An operator's approval is recorded the moment they give it; whether the provider
  then carried it out is a separate fact that can fail on its own. A two-state
  answer would either lose an approval that really happened or claim a card exists
  when the provider said no."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [kotoba.card.actuation :as actuation]
            [langgraph.graph :as g]
            [cardissuing.http :as http]
            [cardissuing.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(defonce ^HttpClient client
  (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 5)) (.build)))

(def token "test-card-operator-token")

(defn- req [port path]
  (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path))))

(defn- send-it [builder]
  (let [res (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode res)
     :body (try (json/read-str (.body res) :key-fn keyword) (catch Exception _ nil))}))

(defn- post [port path body & [header value]]
  (let [b (-> (req port path)
              (.timeout (Duration/ofSeconds 10))
              (.header "Content-Type" "application/json"))
        b (cond-> b value (.header header value))]
    (send-it (.POST b (HttpRequest$BodyPublishers/ofString (json/write-str body))))))

(defn- get! [port path]
  (send-it (.GET (.timeout (req port path) (Duration/ofSeconds 10)))))

(defn- with-actor
  "Run f with both surfaces on ephemeral ports. f receives
  [consent-port operator-port store app]."
  [actuator f]
  (let [st (store/seed-db)
        running (http/start! {:port 0 :operator-port 0 :store st :actuator actuator})
        cport (.getPort (.getAddress ^com.sun.net.httpserver.HttpServer (:consent running)))
        oport (.getPort (.getAddress ^com.sun.net.httpserver.HttpServer (:operator running)))]
    (try (f cport oport st (:app running))
         (finally (http/stop! running)))))

;; An actuator that records what it was asked and answers as told. No network.
(defn- recording-actuator [answer calls]
  (reify
    actuation/ICardActuation
    (issue-card! [_ cardholder program approval idem]
      (swap! calls conj {:op :issue-card! :cardholder cardholder :program program
                         :approval approval :idempotency-key idem})
      answer)
    (set-card-state! [_ reference event approval idem]
      (swap! calls conj {:op :set-card-state! :reference reference :event event
                         :approval approval :idempotency-key idem})
      answer)
    (card-state [_ _] nil)
    actuation/ICardholderActuation
    (create-cardholder! [_ application approval idem]
      (swap! calls conj {:op :create-cardholder! :application application
                         :approval approval :idempotency-key idem})
      answer)
    (cardholder [_ _] nil)))

(defn- proposal
  ([op value] (proposal op value "p-1"))
  ([op value id]
   {:proposal {:id id :authority "card" :op op :value value
               :digest "digest-1" :status "approved"
               :passkey-credential-id "cred-1"}}))

;; ---------------------------------------------------------------------------
;; Reaching an op that an operator actually decides.
;;
;; :cardholder/intake AUTO-COMMITS at the actor's phase 3 (it is the one member of
;; that phase's :auto set), so it never becomes pending and never carries an
;; operator approval. The ops an operator decides are the ones permanently absent
;; from every :auto set -- :card/issue among them -- and :card/issue is HELD until
;; this actor's own recorded state satisfies the governor: KYC cleared, BIN
;; assessed and sponsored. So the prelude below drives those through the actor
;; first, exactly as the demo does, and only then is there something to approve.
;; ---------------------------------------------------------------------------

(def operator-ctx {:actor-id "op-1" :actor-role :issuer-ops :phase 3})

(defn- exec-op! [app tid request]
  (g/run* app {:request request :context operator-ctx} {:thread-id tid}))

(defn- approve-thread! [app tid]
  (g/run* app {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn- ready-to-issue!
  "Drive ch-1 to the state where :card/issue is governor-clean: BIN assessed,
  BIN sponsored, KYC cleared. Each of those escalates and is approved here."
  [app]
  (doseq [[tid request]
          [["pre-a" {:op :bin/assess :subject "400000"}]
           ["pre-s" {:op :bin/sponsor :subject "400000"
                     :sponsor-bank "Kotoba Trust Bank"}]
           ["pre-k" {:op :kyc/screen :subject "ch-1"}]]]
    (exec-op! app tid request)
    (approve-thread! app tid)))

(def issue-value
  "A :card/issue proposal value. :stripe-cardholder-id is what the provider needs
  and this actor's own ch-1 is NOT -- see http/actuate!."
  {:cardholder-id "ch-1" :stripe-cardholder-id "ich_123"
   :card-type "virtual" :currency "usd"})

(defn- pending-issue!
  "Push a :card/issue proposal to pending and return its reference."
  [app port & [value id]]
  (ready-to-issue! app)
  (let [{:keys [body]} (post port "/commit"
                             (proposal "card/issue" (or value issue-value)
                                       (or id "p-1")))]
    (assert (= "pending" (:status body)) (pr-str body))
    (:reference body)))

;; ---------------------------------------------------------------------------

(deftest healthz-answers
  (with-actor nil
    (fn [port _ _ _]
      (let [{:keys [status body]} (get! port "/healthz")]
        (is (= 200 status))
        (is (= "ok" (:status body)))
        (is (= "cloud-itonami-card-issuing" (:actor body)))
        (is (pos? (:cardholders body)))))))

(deftest an-auto-committed-op-says-it-was-not-actuated
  (testing "phase 3 auto-commits :cardholder/intake, which means no operator
            approved it and nothing reached the provider. \"committed\" must not
            read as \"the cardholder exists at Stripe\"."
    (with-actor nil
      (fn [port _ _ _]
        (let [{:keys [body]} (post port "/commit"
                                   (proposal "cardholder/intake"
                                             {:cardholder-id "ch-1" :id "ch-1"
                                              :status "intake"}))]
          (is (= "committed" (:status body)))
          (is (= "not-attempted" (:actuation body)))
          (is (= "auto-committed-without-operator-approval"
                 (:actuation-reason body))))))))

(deftest an-actuating-op-ends-pending-not-committed
  (testing "the two gates on the wire: the subject consented, and this actor still
            needs its own operator"
    (with-actor nil
      (fn [port _ _ app]
        (ready-to-issue! app)
        (let [{:keys [body]} (post port "/commit" (proposal "card/issue" issue-value))]
          (is (= "pending" (:status body)))
          (is (string? (:reference body))))))))

(deftest the-consent-surface-cannot-decide
  (with-actor nil
    (fn [port _ _ app]
      (let [ref (pending-issue! app port)]
        (doseq [path [(str "/proposals/" ref "/decide") "/decide"]]
          (let [{:keys [status body]} (post port path {:status "approved" :by "app"})]
            (is (= 404 status) (str "consent surface must not route " path))
            (is (= "held" (:status body)))))
        (is (= "pending" (:status (:body (get! port (str "/proposals/" ref))))))))))

(deftest the-operator-surface-fails-closed-without-a-token
  (testing "an unauthenticated decide here can issue a real payment card"
    (with-actor nil
      (fn [port oport _ app]
        (let [ref (pending-issue! app port)
              {:keys [status body]} (post oport (str "/proposals/" ref "/decide")
                                          {:status "approved" :by "op"})]
          (is (= 503 status))
          (is (= "operator-surface-unconfigured"
                 (name (keyword (get-in body [:refusal :rule]))))))))))

(deftest a-wrong-token-is-refused
  (with-redefs [http/operator-token (constantly token)]
    (with-actor nil
      (fn [port oport _ app]
        (let [ref (pending-issue! app port)
              {:keys [status body]} (post oport (str "/proposals/" ref "/decide")
                                          {:status "approved" :by "op"}
                                          "X-CARD-OPERATOR-TOKEN" "wrong")]
          (is (= 401 status))
          (is (= "operator-token-mismatch"
                 (name (keyword (get-in body [:refusal :rule]))))))))))

(deftest an-approval-nobody-is-named-for-is-refused
  (with-redefs [http/operator-token (constantly token)]
    (with-actor nil
      (fn [port oport _ app]
        (let [ref (pending-issue! app port)]
          (doseq [b [{:status "approved"} {:status "approved" :by ""}
                     {:status "maybe" :by "op"}]]
            (let [{:keys [body]} (post oport (str "/proposals/" ref "/decide") b
                                       "X-CARD-OPERATOR-TOKEN" token)]
              (is (= "held" (:status body)) (pr-str b)))))))))

;; ---------------------------------------------------------------------------
;; approving and issuing are different events
;; ---------------------------------------------------------------------------

(defn- decide! [oport ref by-status]
  (post oport (str "/proposals/" ref "/decide") by-status
        "X-CARD-OPERATOR-TOKEN" token))

(deftest with-no-actuator-an-approval-is-recorded-but-not-carried-out
  (testing "honest about a deployment that can approve and cannot issue, rather
            than reporting success"
    (with-redefs [http/operator-token (constantly token)]
      (with-actor nil
        (fn [port oport _ app]
          (let [ref (pending-issue! app port)
                {:keys [body]} (decide! oport ref {:status "approved"
                                                   :by "operator@example"})]
            (is (= "approved-not-actuated" (:status body)))
            (is (true? (:approval-recorded body))
                "the approval really happened and must not be discarded")
            (is (= "no-actuator-configured"
                   (name (keyword (get-in body [:refusal :rule])))))
            (is (= "operator@example" (:decided-by body)))))))))

(deftest a-provider-refusal-does-not-discard-the-approval
  (with-redefs [http/operator-token (constantly token)]
    (let [calls (atom [])
          declining (recording-actuator
                     {:card/ok? false
                      :card/refusal {:rule :stripe-error :detail {:code "card_declined"}}}
                     calls)]
      (with-actor declining
        (fn [port oport st app]
          (let [ref (pending-issue! app port)
                {:keys [body]} (decide! oport ref {:status "approved"
                                                   :by "operator@example"})]
            (is (= "approved-not-actuated" (:status body)))
            (is (= "stripe-error" (name (keyword (get-in body [:refusal :rule])))))
            (is (= 1 (count @calls)) "the provider was asked, and said no")
            (testing "and the ledger still names the approver"
              (is (some #(and (= :committed (:t %))
                              (= "operator@example" (:approved-by %)))
                        (store/ledger st))))))))))

(deftest a-successful-actuation-is-reported-as-committed-with-the-provider-record
  (with-redefs [http/operator-token (constantly token)]
    (let [calls (atom [])
          ok (recording-actuator
              {:card/ok? true :card/reference "ic_123" :card/state :issued
               :card/provider-record {:id "ic_123" :status "inactive"}}
              calls)]
      (with-actor ok
        (fn [port oport _ app]
          (let [ref (pending-issue! app port)
                {:keys [body]} (decide! oport ref {:status "approved"
                                                   :by "operator@example"})]
            (is (= "committed" (:status body)))
            (is (some? (get-in body [:record :provider])))
            (is (= 1 (count @calls)))
            (is (= :issue-card! (:op (first @calls))))
            (testing "and it was given STRIPE's cardholder id, not ch-1"
              (is (= "ich_123" (:cardholder (first @calls)))))))))))

(deftest an-issue-with-no-stripe-cardholder-is-refused-before-any-call
  (testing "substituting this actor's ch-1 for a Stripe ich_… would create a card
            against a cardholder that does not exist"
    (with-redefs [http/operator-token (constantly token)]
      (let [calls (atom [])
            ok (recording-actuator {:card/ok? true} calls)]
        (with-actor ok
          (fn [port oport _ app]
            (let [ref (pending-issue! app port
                                      (dissoc issue-value :stripe-cardholder-id))
                  {:keys [body]} (decide! oport ref {:status "approved"
                                                     :by "operator@example"})]
              (is (= "approved-not-actuated" (:status body)))
              (is (= "stripe-cardholder-unknown"
                     (name (keyword (get-in body [:refusal :rule])))))
              (is (empty? @calls) "refused before reaching the provider"))))))))

(deftest the-actuator-receives-a-named-approval-and-a-derived-idempotency-key
  (with-redefs [http/operator-token (constantly token)]
    (let [calls (atom [])
          ok (recording-actuator {:card/ok? true} calls)]
      (with-actor ok
        (fn [port oport _ app]
          (let [ref (pending-issue! app port)]
            (decide! oport ref {:status "approved" :by "operator@example"})
            (let [c (first @calls)]
              (is (= "operator@example" (get-in c [:approval :by])))
              (is (= ref (get-in c [:approval :reference])))
              (testing "the key is derived from the reference, so a retried decide
                        cannot issue a second card"
                (is (= (http/idempotency-key-for ref) (:idempotency-key c)))))))))))

(deftest a-rejection-never-reaches-the-actuator
  (with-redefs [http/operator-token (constantly token)]
    (let [calls (atom [])
          ok (recording-actuator {:card/ok? true} calls)]
      (with-actor ok
        (fn [port oport _ app]
          (let [ref (pending-issue! app port)
                {:keys [body]} (decide! oport ref {:status "rejected"
                                                   :by "operator@example"})]
            (is (not= "committed" (:status body)))
            (is (empty? @calls) "a rejected proposal must not be carried out")))))))

(deftest an-actuator-that-throws-is-a-refusal-not-a-500
  (with-redefs [http/operator-token (constantly token)]
    (let [boom (reify actuation/ICardActuation
                 (issue-card! [_ _ _ _ _] (throw (ex-info "boom" {})))
                 (set-card-state! [_ _ _ _ _] (throw (ex-info "boom" {})))
                 (card-state [_ _] nil))]
      (with-actor boom
        (fn [port oport _ app]
          (let [ref (pending-issue! app port)
                {:keys [status body]} (decide! oport ref {:status "approved"
                                                          :by "op"})]
            (is (= 200 status))
            (is (= "approved-not-actuated" (:status body)))
            (is (= "actuator-threw"
                   (name (keyword (get-in body [:refusal :rule])))))))))))

;; ---------------------------------------------------------------------------
;; reads and refusals
;; ---------------------------------------------------------------------------

(deftest an-unknown-reference-answers-unknown-not-pending
  (with-actor nil
    (fn [port _ _ _]
      (is (= "unknown" (:status (:body (get! port "/proposals/never-existed"))))))))

(deftest a-governor-refusal-comes-back-as-held
  (with-actor nil
    (fn [port _ _ _]
      (testing "an unknown op is refused rather than coerced"
        (doseq [op ["card/nuke" ""]]
          (let [{:keys [body]} (post port "/commit" (proposal op {}))]
            (is (= "held" (:status body)) (pr-str op))))))))

(deftest an-issue-the-governor-is-not-ready-for-is-held-not-pending
  (testing "with no KYC and no BIN sponsorship, :card/issue is HELD -- it never
            reaches an operator, so no approval can override it"
    (with-actor nil
      (fn [port _ _ _]
        (let [{:keys [body]} (post port "/commit" (proposal "card/issue" issue-value))]
          (is (= "held" (:status body)))
          (is (seq (get-in body [:refusal :violations]))))))))

(deftest a-malformed-request-is-refused-with-a-reason
  (with-actor nil
    (fn [port _ _ _]
      (let [{:keys [status body]} (post port "/commit" {:nope true})]
        (is (= 400 status))
        (is (= "malformed-request" (name (keyword (get-in body [:refusal :rule])))))))))

(deftest every-answer-is-one-of-the-declared-states
  (with-actor nil
    (fn [port _ _ _]
      (doseq [[op value] [["cardholder/intake" {:cardholder-id "ch-1" :id "ch-1"
                                                :status "intake"}]
                          ["card/nuke" {}]
                          ["card/issue" issue-value]
                          ["bin/sponsor" {:bin "411111"}]]]
        (let [{:keys [body]} (post port "/commit" (proposal op value))]
          (is (contains? #{"committed" "held" "pending" "approved-not-actuated"}
                         (:status body))
              (str op " -> " (:status body))))))))
