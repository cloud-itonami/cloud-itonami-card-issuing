(ns cardissuing.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [cardissuing.registry :as registry]))

(deftest luhn-check-digit-matches-a-known-test-number
  (testing "4111111111111111 is a well-known Luhn-valid test PAN -- payload '411111111111111' must yield check digit 1"
    (is (= 1 (registry/luhn-check-digit "411111111111111")))))

(deftest luhn-valid-accepts-known-valid-numbers
  (is (registry/luhn-valid? "4111111111111111"))
  (is (registry/luhn-valid? "79927398713")))

(deftest luhn-valid-rejects-corrupted-numbers
  (is (not (registry/luhn-valid? "4111111111111112")))
  (is (not (registry/luhn-valid? "79927398710"))))

(deftest luhn-valid-rejects-non-numeric-or-too-short
  (is (not (registry/luhn-valid? "not-a-number")))
  (is (not (registry/luhn-valid? "1")))
  (is (not (registry/luhn-valid? nil))))

(deftest assign-card-reference-is-always-luhn-valid
  (testing "every card reference this registry constructs passes its own Luhn checksum -- the ground truth cardissuing.governor independently recomputes"
    (doseq [seq-n [0 1 42 999999999]]
      (let [ref (registry/assign-card-reference "400000" seq-n)]
        (is (= 16 (count ref)))
        (is (registry/luhn-valid? ref))
        (is (= "400000" (subs ref 0 6)))))))

(deftest assign-card-reference-rejects-a-malformed-bin
  (is (thrown? Exception (registry/assign-card-reference "4000" 0)))
  (is (thrown? Exception (registry/assign-card-reference "ABCDEF" 0))))

(deftest masked-shows-only-bin-and-last-4
  (let [ref (registry/assign-card-reference "400000" 1)]
    (is (= (str (subs ref 0 6) "******" (subs ref 12 16)) (registry/masked ref)))))

(deftest register-sponsorship-builds-a-draft-record
  (let [result (registry/register-sponsorship "Visa" "Kotoba Trust Bank" "400000" 1000 "JPN")]
    (is (= "sponsorship-draft" (get (get result "record") "kind")))
    (is (= "400000" (get (get result "record") "bin")))
    (is (false? (get (get result "certificate") "issued_by_scheme")))))

(deftest register-card-issuance-assigns-a-luhn-valid-reference
  (let [result (registry/register-card-issuance "ch-1" "400000" 0 "fa-1" "JPN")]
    (is (registry/luhn-valid? (get result "card_reference")))
    (is (= "issuance-draft" (get (get result "record") "kind")))
    (is (= "ch-1" (get (get result "record") "cardholder_id")))))

(deftest register-lifecycle-event-rejects-unknown-events
  (is (thrown? Exception
               (registry/register-lifecycle-event "4000000000000000" :teleport "2026-07-25" "x"))))

(deftest register-dispute-requires-transaction-and-reason
  (is (thrown? Exception (registry/register-dispute "" "reason" "2026-07-25")))
  (is (thrown? Exception (registry/register-dispute "tx-1" "" "2026-07-25")))
  (let [result (registry/register-dispute "tx-1" "unrecognized charge" "2026-07-25")]
    (is (= "dispute-draft" (get (get result "record") "kind")))))

(deftest append-never-mutates-history-in-place
  (let [h1 []
        result (registry/register-dispute "tx-1" "reason" "2026-07-25")
        h2 (registry/append h1 result)]
    (is (= [] h1) "original history untouched")
    (is (= 1 (count h2)))))
