(ns cardissuing.llm-advisor-test
  "The real-inference advisor (langchain.model ChatModel), driven offline
  by langchain's mock-model. Proves: a real LLM proposal is parsed,
  still fully censored by the Card Issuing Governor, and that an
  unparseable/garbage response -- or one that fabricates a
  jurisdiction's requirements, or one that answers a harmless-looking
  request with a mismatched, higher-stakes :effect -- can never
  auto-sponsor, auto-issue or auto-authorize."
  (:require [clojure.test :refer [deftest is testing]]
            [langchain.model :as model]
            [cardissuing.cardissuingadvisor :as cardissuingadvisor]
            [cardissuing.governor :as governor]
            [cardissuing.store :as store]))

(def operator {:actor-id "op-1" :actor-role :issuer-ops :phase 3})
(def assess-req {:op :bin/assess :subject "400000"})

(defn- advise-with [req content]
  (cardissuingadvisor/-advise
   (cardissuingadvisor/llm-advisor (model/mock-model [{:role :assistant :content content}]))
   (store/seed-db) req))

(deftest clean-llm-assessment-is-parsed-and-accepted
  (let [p (advise-with assess-req
                       (str "{:summary \"JPN 向けBIN/レンジ要件を提案\" :rationale \"METIの公式ソースに基づく\" "
                            ":cites [\"割賦販売法\" \"https://www.meti.go.jp/policy/economy/consumer_transaction/credit/index.html\"] "
                            ":effect :bin-assessment/set "
                            ":value {:jurisdiction \"JPN\" :checklist [] :spec-basis \"https://www.meti.go.jp/policy/economy/consumer_transaction/credit/index.html\"} "
                            ":stake nil :confidence 0.9}"))]
    (is (= :bin-assessment/set (:effect p)))
    (is (seq (:cites p)))
    (is (= 0.9 (:confidence p)))
    (testing "the governor accepts a proposal that actually cites a spec-basis"
      (is (:ok? (governor/check assess-req operator p (store/seed-db)))))))

(deftest llm-fabricating-a-jurisdiction-is-rejected
  (testing "even a confident LLM can't invent a jurisdiction's card-issuing requirements -- spec-basis gate holds"
    (let [p (advise-with assess-req
                         (str "{:summary \"ATL 向けBIN要件を提案\" :rationale \"一般的な慣行に基づく推測\" "
                              ":cites [] :effect :bin-assessment/set "
                              ":value {:jurisdiction \"ATL\" :checklist [\"some requirement\"]} "
                              ":confidence 0.95}"))
          v (governor/check assess-req operator p (store/seed-db))]
      (is (:hard? v))
      (is (some #{:no-spec-basis} (map :rule (:violations v)))))))

(deftest llm-declaring-a-sanctions-hit-is-unoverridable
  (testing "an LLM-reported sanctions hit still forces HOLD, regardless of confidence"
    (let [p (advise-with {:op :kyc/screen :subject "ch-1"}
                         (str "{:summary \"制裁リスト一致\" :rationale \"screening provider hit\" "
                              ":cites [:sanctions-list] :effect :kyc/set "
                              ":value {:cardholder-id \"ch-1\" :verdict :hit} :confidence 0.98}"))
          v (governor/check {:op :kyc/screen :subject "ch-1"} operator p (store/seed-db))]
      (is (:hard? v))
      (is (some #{:sanctions-hit} (map :rule (:violations v)))))))

(deftest llm-answering-a-harmless-request-with-a-mismatched-high-stakes-effect-is-rejected
  (testing "a harmless-looking bin/assess request answered with :effect :card/mark-issued must never
            let a real card-issuance mutation slip through under assessment-level scrutiny"
    (let [p (advise-with assess-req
                         (str "{:summary \"カード発行完了\" :rationale \"承認済み\" "
                              ":cites [] :effect :card/mark-issued "
                              ":value {:cardholder-id \"ch-1\"} :stake :actuation :confidence 0.99}"))
          v (governor/check assess-req operator p (store/seed-db))]
      (is (:hard? v))
      (is (some #{:effect-mismatch} (map :rule (:violations v)))))))

(deftest unparseable-llm-output-never-auto-commits
  (testing "garbage / refusal -> safe noop at confidence 0 -> governor won't pass it"
    (let [p (advise-with assess-req "申し訳ございませんが、その法域についてはお答えできません。")]
      (is (= :noop (:effect p)))
      (is (= 0.0 (:confidence p)))
      (let [v (governor/check assess-req operator p (store/seed-db))]
        (is (not (:ok? v)))))))

(deftest llm-proposing-approve-past-a-velocity-limit-is-caught-independently
  (testing "even a confident LLM proposing :approve past the funding account's own daily-limit is caught by the governor's own recompute, not trusted from the proposal"
    (let [db (store/seed-db)
          _ (store/commit-record! db {:effect :sponsorship/mark-active :path ["400000"]
                                      :value {:scheme "Visa" :sponsor-bank "Kotoba Trust Bank"
                                             :range-size 1000 :jurisdiction "JPN"}})
          _ (store/commit-record! db {:effect :kyc/set :path ["ch-1"]
                                      :payload {:cardholder-id "ch-1" :verdict :clear}})
          _ (store/commit-record! db {:effect :card/mark-issued :path ["ch-1"]})
          _ (store/commit-record! db {:effect :card/lifecycle-applied :path ["ch-1"]
                                      :value {:event :activate :effective-date "2026-07-25" :reason "onboarding"}})
          req {:op :authorization/decide :subject "ch-1"}
          p (advise-with req
                         (str "{:summary \"承認\" :rationale \"問題なし\" :cites [] "
                              ":effect :authorization/decision-recorded "
                              ":value {:transaction-id \"tx-9\" :decision :approve :amount 999999 :mcc \"5411\"} "
                              ":stake :actuation :confidence 0.99}"))
          v (governor/check req operator p db)]
      (is (:hard? v))
      (is (some #{:velocity-limit-exceeded} (map :rule (:violations v)))))))
