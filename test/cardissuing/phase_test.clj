(ns cardissuing.phase-test
  "The phase table as executable tests. The single invariant this repo
  cannot regress on: `:bin/sponsor`, `:card/issue`, `:card/lifecycle`,
  `:authorization/decide` and `:dispute/initiate` must NEVER be a member
  of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [cardissuing.phase :as phase]))

(def actuation-ops
  "Every op that touches a real BIN sponsorship / card issuance / card
  lifecycle / authorization decision / dispute initiation. This set is
  the single source of truth for
  `actuation-ops-never-auto-at-any-phase` below -- add a new actuation
  op here, not just in cardissuing.phase, so a forgotten :auto
  exclusion fails loudly instead of silently."
  #{:bin/sponsor :card/issue :card/lifecycle :authorization/decide :dispute/initiate})

(deftest actuation-ops-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in future entries, auto-commits a real actuation op"
    (doseq [[n {:keys [auto]}] phase/phases
            op actuation-ops]
      (is (not (contains? auto op))
          (str "phase " n " must not auto-commit " op)))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-intake
  (is (= #{:cardholder/intake} (:auto (get phase/phases 3)))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :cardholder/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :card/issue} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :cardholder/intake} :commit)))))

(deftest gate-passes-reads-through-unchanged
  (is (= :commit (:disposition (phase/gate 0 {:op :coverage/report} :commit)))))

(deftest verdict->disposition-mapping
  (is (= :hold (phase/verdict->disposition {:hard? true})))
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true})))
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))
