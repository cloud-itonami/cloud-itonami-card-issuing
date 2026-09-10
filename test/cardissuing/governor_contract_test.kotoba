(ns cardissuing.governor-contract-test
  "The governor contract as executable tests -- the card-issuing analog
  of `formation.governor-contract-test`/`banking.governor-contract-
  test`. The single invariant under test:

    Card Issuing Advisor never sponsors a BIN, issues a card, applies a
    lifecycle transition, decides an authorization or initiates a
    dispute the Card Issuing Governor would reject, none of those five
    ops ever auto-commit at any phase, and every decision (commit OR
    hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [cardissuing.store :as store]
            [cardissuing.governor :as governor]
            [cardissuing.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :issuer-ops :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1" {:op :cardholder/intake :subject "ch-1"
                                 :patch {:id "ch-1" :status :intake}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 1 (count (store/ledger db))))))

(deftest bin-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :bin/assess :subject "400000"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/bin-assessment-of db "400000")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a bin/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3" {:op :bin/assess :subject "999999" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/bin-assessment-of db "999999"))))))

(deftest sanctions-hit-is-held-and-unoverridable
  (testing "a sanctions/PEP hit on a cardholder -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :kyc/screen :subject "ch-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:sanctions-hit} (-> (store/ledger db) first :basis)))
      (is (nil? (store/kyc-of db "ch-3"))))))

(deftest issuance-without-kyc-is-held
  (testing "card/issue before any KYC screening -> HOLD (kyc-incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :card/issue :subject "ch-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:kyc-incomplete} (-> (store/ledger db) last :basis))))))

(defn- sponsor-bin! [actor tid bin]
  (exec-op actor (str tid "-a") {:op :bin/assess :subject bin} operator)
  (approve! actor (str tid "-a"))
  (exec-op actor (str tid "-b") {:op :bin/sponsor :subject bin :sponsor-bank "Kotoba Trust Bank"} operator)
  (approve! actor (str tid "-b")))

(defn- clear-kyc! [actor tid cardholder-id]
  (exec-op actor tid {:op :kyc/screen :subject cardholder-id} operator)
  (approve! actor tid))

(deftest issuance-without-bin-sponsorship-is-held
  (testing "card/issue on an unsponsored BIN, even with clean KYC -> HOLD (bin-not-sponsored)"
    (let [[db actor] (fresh)]
      (clear-kyc! actor "t6a" "ch-1")
      (let [res (exec-op actor "t6" {:op :card/issue :subject "ch-1"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:bin-not-sponsored} (-> (store/ledger db) last :basis)))))))

(deftest issuance-with-unverified-funding-account-is-held
  (testing "card/issue against an unverified funding account -> HOLD, even with clean KYC and a sponsored BIN"
    (let [[db actor] (fresh)]
      (sponsor-bin! actor "t7s" "400000")
      (clear-kyc! actor "t7k" "ch-5")
      (let [res (exec-op actor "t7" {:op :card/issue :subject "ch-5"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:funding-account-unverified} (-> (store/ledger db) last :basis)))))))

(defn- issue-and-activate-ch1!
  "Drive ch-1 all the way to :active (assess -> approve, sponsor -> approve,
  KYC -> approve, issue -> approve, activate -> approve). Shared setup for
  the lifecycle/authorization/dispute tests below."
  [actor]
  (sponsor-bin! actor "setup-s" "400000")
  (clear-kyc! actor "setup-k" "ch-1")
  (exec-op actor "setup-i" {:op :card/issue :subject "ch-1"} operator)
  (approve! actor "setup-i")
  (exec-op actor "setup-a" {:op :card/lifecycle :subject "ch-1" :event :activate
                            :effective-date "2026-07-25" :reason "onboarding"} operator)
  (approve! actor "setup-a"))

(deftest issuance-succeeds-through-approval
  (testing "a clean, fully-ready issuance still ALWAYS interrupts for human approval -- actuation is never auto"
    (let [[db actor] (fresh)]
      (sponsor-bin! actor "t8s" "400000")
      (clear-kyc! actor "t8k" "ch-1")
      (let [r1 (exec-op actor "t8" {:op :card/issue :subject "ch-1"} operator)]
        (is (= :interrupted (:status r1)))
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :issued (:status (store/cardholder db "ch-1"))))
          (is (true? (:card-issued? (store/cardholder db "ch-1"))))
          (is (= 16 (count (:card-reference (store/cardholder db "ch-1"))))))))))

(deftest double-issuance-is-held
  (testing "an already-issued cardholder cannot be issued a second card -> HOLD, un-overridable"
    (let [[db actor] (fresh)]
      (issue-and-activate-ch1! actor)
      (let [res (exec-op actor "t9" {:op :card/issue :subject "ch-1"} operator)]
        (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
        (is (not= :interrupted (:status res)))
        (is (some #{:already-issued} (-> (store/ledger db) last :basis)))))))

(deftest double-sponsorship-is-held
  (testing "an already-sponsored BIN cannot be sponsored a second time -> HOLD"
    (let [[db actor] (fresh)]
      (sponsor-bin! actor "t10s" "400000")
      (let [res (exec-op actor "t10" {:op :bin/sponsor :subject "400000" :sponsor-bank "Another Bank"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:already-sponsored} (-> (store/ledger db) last :basis)))))))

(deftest lifecycle-transition-invalid-before-issuance-is-held
  (testing "activate before a card has ever been issued -> HOLD (lifecycle-transition-invalid)"
    (let [[db actor] (fresh)
          res (exec-op actor "t11" {:op :card/lifecycle :subject "ch-1" :event :activate
                                    :effective-date "2026-07-25" :reason "premature"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:lifecycle-transition-invalid} (-> (store/ledger db) last :basis))))))

(deftest lifecycle-activate-then-block-then-reissue-succeeds-through-approval
  (testing "the full legal lifecycle chain, each transition always escalating for human approval"
    (let [[db actor] (fresh)]
      (issue-and-activate-ch1! actor)
      (is (= :active (:status (store/cardholder db "ch-1"))))
      (exec-op actor "t12a" {:op :card/lifecycle :subject "ch-1" :event :block
                             :effective-date "2026-07-26" :reason "reported lost"} operator)
      (approve! actor "t12a")
      (is (= :blocked (:status (store/cardholder db "ch-1"))))
      (let [before (:card-reference (store/cardholder db "ch-1"))]
        (exec-op actor "t12b" {:op :card/lifecycle :subject "ch-1" :event :reissue
                               :effective-date "2026-07-27" :reason "replacement"} operator)
        (approve! actor "t12b")
        (is (= :active (:status (store/cardholder db "ch-1"))) "reissue re-activates the cardholder")
        (is (not= before (:card-reference (store/cardholder db "ch-1"))) "reissue assigns a NEW card reference")))))

(deftest close-from-active-succeeds-then-double-close-is-held
  (let [[db actor] (fresh)]
    (issue-and-activate-ch1! actor)
    (exec-op actor "t13a" {:op :card/lifecycle :subject "ch-1" :event :close
                           :effective-date "2026-08-01" :reason "voluntary closure"} operator)
    (approve! actor "t13a")
    (is (= :closed (:status (store/cardholder db "ch-1"))))
    (let [res (exec-op actor "t13" {:op :card/lifecycle :subject "ch-1" :event :close
                                    :effective-date "2026-08-02" :reason "second attempt"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:lifecycle-transition-invalid} (-> (store/ledger db) last :basis))))))

(deftest post-issue-intake-cannot-smuggle-changes-around-lifecycle
  (testing ":cardholder/intake auto-commits with NO approval -- once issued, it must not be able to
            silently rewrite the funding-account link behind the actuation gate's back"
    (let [[db actor] (fresh)]
      (issue-and-activate-ch1! actor)
      (let [before (store/cardholder db "ch-1")
            res (exec-op actor "t14" {:op :cardholder/intake :subject "ch-1"
                                      :patch {:id "ch-1" :funding-account-ref "fa-2"}} operator)]
        (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt, no auto-commit")
        (is (not= :interrupted (:status res)))
        (is (some #{:post-issue-intake-blocked} (-> (store/ledger db) last :basis)))
        (is (= before (store/cardholder db "ch-1")) "cardholder record completely unchanged")))))

(deftest intake-cannot-fabricate-an-issued-status-with-a-fake-card-reference
  (testing "the ONE op that auto-commits with ZERO human approval at any phase must not be
            able to mint a completely fake 'issued' card out of thin air"
    (let [[db actor] (fresh)
          before (store/cardholder db "ch-1")
          res (exec-op actor "fab" {:op :cardholder/intake :subject "ch-1"
                                    :patch {:id "ch-1" :status :issued
                                           :card-reference "4000000000000000" :card-issued? true}} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt, no auto-commit")
      (is (not= :interrupted (:status res)))
      (is (some #{:intake-forbidden-field} (-> (store/ledger db) last :basis)))
      (is (some #{:intake-forbidden-status} (-> (store/ledger db) last :basis)))
      (is (= before (store/cardholder db "ch-1")))
      (is (empty? (store/registry-history db)) "no card fabricated"))))

(deftest intake-cannot-target-a-different-cardholder-than-its-declared-subject
  (testing "a request declaring subject ch-2 (never issued, so post-issue-intake-violations
            passes it) whose patch's OWN :id names a DIFFERENT, already-issued cardholder must
            not silently rewrite that other cardholder"
    (let [[db actor] (fresh)]
      (issue-and-activate-ch1! actor)
      (let [before (store/cardholder db "ch-1")
            res (exec-op actor "confuse" {:op :cardholder/intake :subject "ch-2"
                                          :patch {:id "ch-1" :funding-account-ref "fa-2"}} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:intake-subject-mismatch} (-> (store/ledger db) last :basis)))
        (is (= before (store/cardholder db "ch-1")) "ch-1 completely unchanged")))))

(deftest intake-before-issuance-still-works-normally
  (testing "the block is post-issue ONLY -- pre-issue intake is unaffected and still auto-commits"
    (let [[db actor] (fresh)
          res (exec-op actor "t15" {:op :cardholder/intake :subject "ch-1"
                                    :patch {:id "ch-1" :name "田中 一郎 (updated)"}} operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= "田中 一郎 (updated)" (:name (store/cardholder db "ch-1")))))))

(deftest authorization-decide-approve-succeeds-through-approval
  (testing "a clean, within-limits authorization decision still ALWAYS interrupts -- actuation is never auto"
    (let [[db actor] (fresh)]
      (issue-and-activate-ch1! actor)
      (let [r1 (exec-op actor "t16" {:op :authorization/decide :subject "ch-1"
                                     :transaction-id "tx-1" :amount 5000 :mcc "5411"} operator)]
        (is (= :interrupted (:status r1)))
        (let [r2 (approve! actor "t16")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :approve (:decision (store/authorization-decision-of db "tx-1"))))
          (is (= 5000 (:daily-spend-used (store/funding-account-of db "fa-1")))))))))

(deftest authorization-decide-same-transaction-twice-is-held
  (testing "the SAME transaction-id cannot be decided twice -> HOLD, un-overridable"
    (let [[db actor] (fresh)]
      (issue-and-activate-ch1! actor)
      (exec-op actor "t17a" {:op :authorization/decide :subject "ch-1"
                             :transaction-id "tx-1" :amount 5000 :mcc "5411"} operator)
      (approve! actor "t17a")
      (let [res (exec-op actor "t17" {:op :authorization/decide :subject "ch-1"
                                      :transaction-id "tx-1" :amount 1000 :mcc "5411"} operator)]
        (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
        (is (not= :interrupted (:status res)))
        (is (some #{:already-decided} (-> (store/ledger db) last :basis)))))))

(deftest dispute-without-an-approved-decision-is-held
  (testing "no authorization decision on file for the target transaction -> HOLD (dispute-target-invalid)"
    (let [[db actor] (fresh)
          res (exec-op actor "t18" {:op :dispute/initiate :subject "tx-nonexistent"
                                    :reason "never happened" :effective-date "2026-08-01"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:dispute-target-invalid} (-> (store/ledger db) last :basis))))))

(deftest dispute-initiate-succeeds-then-double-dispute-is-held
  (testing "a clean dispute always escalates; the SAME transaction cannot be disputed twice"
    (let [[db actor] (fresh)]
      (issue-and-activate-ch1! actor)
      (exec-op actor "t19a" {:op :authorization/decide :subject "ch-1"
                             :transaction-id "tx-2" :amount 3000 :mcc "5411"} operator)
      (approve! actor "t19a")
      (let [r1 (exec-op actor "t19" {:op :dispute/initiate :subject "tx-2"
                                     :reason "unrecognized charge" :effective-date "2026-08-01"} operator)]
        (is (= :interrupted (:status r1)))
        (let [r2 (approve! actor "t19")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (some? (store/dispute-of db "tx-2")))))
      (let [res (exec-op actor "t20" {:op :dispute/initiate :subject "tx-2"
                                      :reason "again" :effective-date "2026-08-02"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:already-disputed} (-> (store/ledger db) last :basis)))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :cardholder/intake :subject "ch-1" :patch {:id "ch-1" :status :intake}} operator)
      (exec-op actor "b" {:op :bin/assess :subject "999999" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db))) "one commit + one hold, both recorded"))))

(deftest auto-committed-ledger-fact-has-no-fabricated-approver
  (let [[db actor] (fresh)]
    (exec-op actor "t21" {:op :cardholder/intake :subject "ch-1" :patch {:id "ch-1" :status :intake}} operator)
    (is (nil? (:approved-by (last (store/ledger db)))))))

(deftest committed-ledger-fact-records-the-actual-approver
  (let [[db actor] (fresh)]
    (sponsor-bin! actor "t22s" "400000")
    (clear-kyc! actor "t22k" "ch-1")
    (exec-op actor "t22" {:op :card/issue :subject "ch-1"} operator)
    (let [r2 (g/run* actor {:approval {:status :approved :by "supervisor-9"}}
                     {:thread-id "t22" :resume? true})]
      (is (= :commit (get-in r2 [:state :disposition])))
      (is (= "supervisor-9" (:approved-by (last (store/ledger db))))
          "the approver, not the original requester's actor-id"))))

;; ----------------------------- ground-truth governor recompute -----------------------------
;; The following call `governor/check` directly with a hand-crafted
;; proposal (the same technique `cardissuing.llm-advisor-test` uses for a
;; possibly-hallucinating real LLM) to prove these checks are enforced
;; even when the proposal itself claims :decision :approve --
;; `cardissuing.cardissuingadvisor/mock-advisor`'s own honest arithmetic
;; never actually produces a violating :approve proposal, so these
;; invariants can only be exercised this way through the DEFAULT mock
;; advisor, not through the full actor's normal happy path.

(deftest velocity-limit-exceeded-is-caught-even-if-the-proposal-claims-approve
  (let [[db actor] (fresh)]
    (issue-and-activate-ch1! actor)
    (let [request {:op :authorization/decide :subject "ch-1"}
          proposal {:effect :authorization/decision-recorded
                    :value {:transaction-id "tx-9" :decision :approve :amount 999999 :mcc "5411"}
                    :cites [] :stake :actuation :confidence 0.99}
          v (governor/check request operator proposal db)]
      (is (:hard? v))
      (is (some #{:velocity-limit-exceeded} (map :rule (:violations v)))))))

(deftest mcc-restricted-is-caught-even-if-the-proposal-claims-approve
  (let [[db actor] (fresh)]
    (issue-and-activate-ch1! actor)
    (let [request {:op :authorization/decide :subject "ch-1"}
          proposal {:effect :authorization/decision-recorded
                    :value {:transaction-id "tx-9" :decision :approve :amount 100 :mcc "7995"}
                    :cites [] :stake :actuation :confidence 0.99}
          v (governor/check request operator proposal db)]
      (is (:hard? v))
      (is (some #{:mcc-restricted} (map :rule (:violations v)))))))

(deftest insufficient-funds-is-caught-even-if-the-proposal-claims-approve
  (let [[db actor] (fresh)]
    (issue-and-activate-ch1! actor)
    (let [request {:op :authorization/decide :subject "ch-1"}
          proposal {:effect :authorization/decision-recorded
                    :value {:transaction-id "tx-9" :decision :approve :amount 5000000 :mcc "5411"}
                    :cites [] :stake :actuation :confidence 0.99}
          v (governor/check request operator proposal db)]
      (is (:hard? v))
      (is (some #{:insufficient-funds} (map :rule (:violations v)))))))

(deftest a-clean-approve-within-limits-is-not-hard-violated
  (let [[db actor] (fresh)]
    (issue-and-activate-ch1! actor)
    (let [request {:op :authorization/decide :subject "ch-1"}
          proposal {:effect :authorization/decision-recorded
                    :value {:transaction-id "tx-9" :decision :approve :amount 500 :mcc "5411"}
                    :cites [] :stake :actuation :confidence 0.9}
          v (governor/check request operator proposal db)]
      (is (not (:hard? v)))
      (is (:escalate? v) "still escalates -- actuation is never auto"))))

(deftest effect-mismatch-is-a-hard-violation
  (let [[db _actor] (fresh)
        request {:op :bin/assess :subject "400000"}
        proposal {:effect :card/mark-issued :value {} :cites ["x"] :confidence 0.9}
        v (governor/check request operator proposal db)]
    (is (:hard? v))
    (is (some #{:effect-mismatch} (map :rule (:violations v))))))
