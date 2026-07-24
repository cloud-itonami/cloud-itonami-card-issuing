(ns cardissuing.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [cardissuing.facts :as facts]))

(deftest known-jurisdictions-have-a-real-spec-basis
  (doseq [iso3 ["JPN" "USA" "GBR" "DEU"]]
    (testing iso3
      (let [sb (facts/spec-basis iso3)]
        (is (some? sb))
        (is (seq (:provenance sb)))
        (is (seq (:legal-basis sb)))
        (is (seq (:required-evidence sb)))))))

(deftest unknown-jurisdiction-has-no-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest coverage-is-honest
  (let [c (facts/coverage ["JPN" "USA" "ATL" "XYZ"])]
    (is (= 4 (:requested c)))
    (is (= 2 (:covered c)))
    (is (= ["JPN" "USA"] (:covered-jurisdictions c)))
    (is (= ["ATL" "XYZ"] (:missing-jurisdictions c)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [ev (facts/evidence-checklist "JPN")]
    (is (facts/required-evidence-satisfied? "JPN" ev))
    (is (not (facts/required-evidence-satisfied? "JPN" (rest ev))))
    (is (not (facts/required-evidence-satisfied? "ATL" ev)) "no spec-basis -> never satisfied")))

(deftest default-restricted-mccs-are-real-iso-18245-codes
  (is (contains? facts/default-restricted-mccs "7995"))
  (is (= 3 (count facts/default-restricted-mccs))))
