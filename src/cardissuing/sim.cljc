(ns cardissuing.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean cardholder through
  intake -> BIN assessment -> KYC screening -> BIN sponsorship (always
  escalates) -> human approval -> card issuance -> activate -> a
  real-time authorization decision -> a dispute initiation (all always
  escalate), then shows several HARD holds (a sanctions hit, a
  fabricated jurisdiction, a double-issuance attempt) that never reach a
  human at all, and prints the audit ledger + the draft registry record
  history."
  (:require [langgraph.graph :as g]
            [cardissuing.store :as store]
            [cardissuing.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :issuer-ops :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== cardholder/intake ch-1 (JPN, clean cardholder) ==")
    (println (exec! actor "t1" {:op :cardholder/intake :subject "ch-1"
                                :patch {:id "ch-1" :status :intake}} operator))

    (println "== bin/assess 400000 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :bin/assess :subject "400000"} operator))
    (println (approve! actor "t2"))

    (println "== kyc/screen ch-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :kyc/screen :subject "ch-1"} operator))
    (println (approve! actor "t3"))

    (println "== bin/sponsor 400000 (always escalates -- actuation) ==")
    (let [r (exec! actor "t4" {:op :bin/sponsor :subject "400000" :sponsor-bank "Kotoba Trust Bank"} operator)]
      (println r)
      (println "-- human operator approves --")
      (println (approve! actor "t4")))

    (println "== card/issue ch-1 (always escalates -- actuation) ==")
    (let [r (exec! actor "t5" {:op :card/issue :subject "ch-1"} operator)]
      (println r)
      (println "-- human operator approves --")
      (println (approve! actor "t5")))

    (println "== card/lifecycle ch-1 :activate (always escalates -- actuation) ==")
    (let [r (exec! actor "t6" {:op :card/lifecycle :subject "ch-1" :event :activate
                               :effective-date "2026-07-25" :reason "cardholder onboarding"} operator)]
      (println r)
      (println "-- human operator approves --")
      (println (approve! actor "t6")))

    (println "== authorization/decide ch-1 tx-1 (5000 JPY, MCC 5411, always escalates -- actuation) ==")
    (let [r (exec! actor "t7" {:op :authorization/decide :subject "ch-1"
                               :transaction-id "tx-1" :amount 5000 :mcc "5411"} operator)]
      (println r)
      (println "-- human operator approves --")
      (println (approve! actor "t7")))

    (println "== dispute/initiate tx-1 (always escalates -- actuation) ==")
    (let [r (exec! actor "t8" {:op :dispute/initiate :subject "tx-1"
                               :reason "cardholder disputes an unrecognized charge"
                               :effective-date "2026-08-01"} operator)]
      (println r)
      (println "-- human operator approves --")
      (println (approve! actor "t8")))

    (println "== card/issue ch-1 AGAIN (already issued -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t9" {:op :card/issue :subject "ch-1"} operator))

    (println "== kyc/screen ch-3 (sanctions hit -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t10" {:op :kyc/screen :subject "ch-3"} operator))

    (println "== bin/assess 999999 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t11" {:op :bin/assess :subject "999999" :no-spec? true} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft registry records ==")
    (doseq [r (store/registry-history db)] (println r))))
