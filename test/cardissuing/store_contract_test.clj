(ns cardissuing.store-contract-test
  "The Store contract. MemStore is the only backend at this actor's R0 maturity (see
  README `Maturity`).

  This docstring used to claim a future Datomic/kotoba-server backend could be dropped
  in behind the same contract \"only re-running them against the new backend
  constructor\" -- while every test called `store/seed-db` directly. You could not
  re-run it against anything without editing it, so the claim was not true of the file
  making it. A boundary nobody can run twice is an assertion, not a boundary.

  `*make-store*` is now that constructor, and `verify-contract!` runs every assertion
  in this namespace against whatever it is given. A second backend is one deftest. Same
  change as cloud-itonami-esim, for the same reason: ADR-2607300300 D4 needs the actors
  to reach a shared ref, and the argument that this is a swap rather than a rewrite is
  only worth as much as its being checkable."
  (:require [clojure.test :as test :refer [deftest is testing]]
            [cardissuing.store :as store]))

(def ^:dynamic *make-store*
  "The constructor under test. Rebound by `verify-contract!`."
  store/seed-db)

(defn- fresh [] (*make-store*))

(deftest read-parity
  (let [s (fresh)]
    (is (= "田中 一郎" (:name (store/cardholder s "ch-1"))))
    (is (= "JPN" (:jurisdiction (store/cardholder s "ch-1"))))
    (is (= "400000" (:bin (store/cardholder s "ch-1"))))
    (is (= :intake (:status (store/cardholder s "ch-1"))))
    (is (false? (:card-issued? (store/cardholder s "ch-1"))))
    (is (true? (:sanctions-hit? (store/cardholder s "ch-3"))))
    (is (= ["ch-1" "ch-2" "ch-3" "ch-4" "ch-5"] (mapv :id (store/all-cardholders s))))
    (is (nil? (store/kyc-of s "ch-1")))
    (is (nil? (store/bin-assessment-of s "400000")))
    (is (nil? (store/sponsorship-of s "400000")))
    (is (= true (:verified? (store/funding-account-of s "fa-1"))))
    (is (= false (:verified? (store/funding-account-of s "fa-2"))))
    (is (= [] (store/ledger s)))
    (is (= [] (store/registry-history s)))
    (is (zero? (store/next-sequence s "400000")))))

(deftest write-and-ledger-parity
  (let [s (fresh)]
    (testing "partial upsert merges, preserving untouched fields"
      (store/commit-record! s {:effect :cardholder/upsert
                               :value {:id "ch-1" :status :intake :nickname "Ichiro"}})
      (is (= "Ichiro" (:nickname (store/cardholder s "ch-1"))))
      (is (= "田中 一郎" (:name (store/cardholder s "ch-1"))) "name preserved"))
    (testing "bin-assessment / kyc payloads commit and read back"
      (store/commit-record! s {:effect :bin-assessment/set :path ["400000"]
                               :payload {:jurisdiction "JPN" :checklist ["a" "b"] :spec-basis "https://example"}})
      (is (= {:jurisdiction "JPN" :checklist ["a" "b"] :spec-basis "https://example"}
             (store/bin-assessment-of s "400000")))
      (store/commit-record! s {:effect :kyc/set :path ["ch-1"]
                               :payload {:cardholder-id "ch-1" :verdict :clear}})
      (is (= {:cardholder-id "ch-1" :verdict :clear} (store/kyc-of s "ch-1"))))
    (testing "sponsorship commits, activates and drafts a registry record"
      (store/commit-record! s {:effect :sponsorship/mark-active :path ["400000"]
                               :value {:scheme "Visa" :sponsor-bank "Kotoba Trust Bank"
                                      :range-size 1000 :jurisdiction "JPN"}})
      (is (true? (:active? (store/sponsorship-of s "400000"))))
      (is (= 1 (count (store/registry-history s)))))
    (testing "card issuance drafts a Luhn-valid card reference and advances the sequence"
      (store/commit-record! s {:effect :card/mark-issued :path ["ch-1"]})
      (let [ch (store/cardholder s "ch-1")]
        (is (= :issued (:status ch)))
        (is (true? (:card-issued? ch)))
        (is (= 16 (count (:card-reference ch))))
        (is (= 1 (store/next-sequence s "400000")))
        (is (= 2 (count (store/registry-history s))))))
    (testing "lifecycle activate transitions status and drafts a record"
      (store/commit-record! s {:effect :card/lifecycle-applied :path ["ch-1"]
                               :value {:event :activate :effective-date "2026-07-25" :reason "onboarding"}})
      (is (= :active (:status (store/cardholder s "ch-1"))))
      (is (= 3 (count (store/registry-history s)))))
    (testing "authorization decision records + advances daily-spend-used on approve"
      (store/commit-record! s {:effect :authorization/decision-recorded :path ["ch-1"]
                               :value {:transaction-id "tx-1" :decision :approve :amount 5000 :mcc "5411"}})
      (is (= :approve (:decision (store/authorization-decision-of s "tx-1"))))
      (is (= 5000 (:daily-spend-used (store/funding-account-of s "fa-1"))))
      (is (= 4 (count (store/registry-history s)))))
    (testing "dispute initiation records"
      (store/commit-record! s {:effect :dispute/mark-initiated :path ["tx-1"]
                               :value {:reason "unrecognized charge" :effective-date "2026-08-01"}})
      (is (some? (store/dispute-of s "tx-1")))
      (is (= 5 (count (store/registry-history s)))))
    (testing "ledger is append-only and order-preserving"
      (store/append-ledger! s {:op :a :disposition :commit})
      (store/append-ledger! s {:op :b :disposition :hold})
      (is (= [:commit :hold] (mapv :disposition (store/ledger s)))))))

(deftest reissue-assigns-a-new-card-reference-and-advances-the-sequence
  (let [s (fresh)]
    (store/commit-record! s {:effect :sponsorship/mark-active :path ["400000"]
                             :value {:scheme "Visa" :sponsor-bank "Kotoba Trust Bank"
                                    :range-size 1000 :jurisdiction "JPN"}})
    (store/commit-record! s {:effect :card/mark-issued :path ["ch-1"]})
    (let [first-ref (:card-reference (store/cardholder s "ch-1"))]
      (store/commit-record! s {:effect :card/lifecycle-applied :path ["ch-1"]
                               :value {:event :activate :effective-date "2026-07-25" :reason "onboarding"}})
      (store/commit-record! s {:effect :card/lifecycle-applied :path ["ch-1"]
                               :value {:event :block :effective-date "2026-07-26" :reason "lost"}})
      (store/commit-record! s {:effect :card/lifecycle-applied :path ["ch-1"]
                               :value {:event :reissue :effective-date "2026-07-27" :reason "replacement"}})
      (let [second-ref (:card-reference (store/cardholder s "ch-1"))]
        (is (not= first-ref second-ref))
        (is (= 16 (count second-ref)))
        (is (= :active (:status (store/cardholder s "ch-1"))))
        (is (= 2 (store/next-sequence s "400000")))))))

;; ---------------------------------------------------------------------------
;; running this contract against another implementation
;; ---------------------------------------------------------------------------

(defn verify-contract!
  "Run every assertion in this namespace against `make-store`.

  A second backend -- whatever eventually gives this actor the shared ref
  ADR-2607300300's D4 asks for -- is added as one deftest:

      (deftest datomic-store-satisfies-the-same-contract
        (verify-contract! #(datomic-store/store connection)))

  and everything this file pins is checked against it without a line of it changing.
  Skips itself and the MemStore entry point, or it would recurse."
  [make-store]
  (binding [*make-store* make-store]
    (doseq [[sym v] (ns-publics 'cardissuing.store-contract-test)
            :when (and (:test (meta v))
                       (not (contains? #{'mem-store-satisfies-the-contract
                                         'the-contract-runner-actually-runs-something}
                                       sym)))]
      (test/test-var v))))

(deftest mem-store-satisfies-the-contract
  (testing "the same entry point a second backend will use, exercised today against the
            only implementation there is -- so the mechanism is not first tried on the
            day it matters"
    (verify-contract! store/seed-db)))

(deftest the-contract-runner-actually-runs-something
  (testing "a runner that silently selected no tests would pass forever and check
            nothing"
    (let [n (count (for [[sym v] (ns-publics 'cardissuing.store-contract-test)
                         :when (and (:test (meta v))
                                    (not (contains? #{'mem-store-satisfies-the-contract
                                                      'the-contract-runner-actually-runs-something}
                                                    sym)))]
                     sym))]
      (is (<= 3 n) (str "expected the contract to be several tests, got " n)))))
