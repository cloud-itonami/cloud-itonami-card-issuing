(ns cardissuing.store
  "SSoT for the card-issuing actor, behind a `Store` protocol so a future
  backend swap (Datomic Local / kotoba-server, the same seam
  `banking.store`/`formation.store` expose via `langchain.db`) is a
  config change, not a rewrite. At this actor's R0 maturity, only
  `MemStore` (an atom of EDN) is implemented -- see README `Maturity`
  and `docs/adr/0001-architecture.md` for the promotion path.

  The ledger stays append-only: 'who sponsored what BIN, who was issued
  which card, who approved which real-time authorization decision, who
  initiated which dispute, approved by whom' is always a query over an
  immutable log -- the audit trail a cardholder (and a card scheme)
  trusting an issuer needs, and the evidence an issuer needs if a
  decision is later disputed or examined by a regulator.

  NOTE: no raw PAN field exists anywhere in this schema, by design, not
  merely omitted-by-convention -- see `cardissuing.registry`'s docstring
  and README `Scope`. `:card-reference` is a synthetic, Luhn-checkable
  identifier this actor's own registry constructs for its OWN draft
  records, never a real network-issued PAN."
  (:require [cardissuing.registry :as registry]))

(defprotocol Store
  (cardholder [s id])
  (all-cardholders [s])
  (kyc-of [s cardholder-id] "committed KYC/sanctions screening verdict for a cardholder, or nil")
  (bin-info [s bin] "static BIN metadata (jurisdiction/scheme) this issuer is considering sponsoring")
  (bin-assessment-of [s bin] "committed BIN/scheme sponsorship-requirement assessment (doc checklist), or nil")
  (sponsorship-of [s bin] "committed BIN/range sponsorship record, or nil")
  (funding-account-of [s account-ref] "the funding account (bank deposit / e-money wallet / credit line) a card links to")
  (authorization-decision-of [s transaction-id] "committed authorization decision for a transaction-id, or nil")
  (dispute-of [s transaction-id] "committed dispute/chargeback-initiation record for a transaction-id, or nil")
  (ledger [s])
  (registry-history [s] "the append-only registry-record history (cardissuing.registry drafts)")
  (next-sequence [s bin] "next card-issuance sequence number for a BIN")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-cardholders [s cardholders] "replace/seed the cardholder directory (map id->cardholder)")
  (with-funding-accounts [s accounts] "replace/seed the funding-account directory (map ref->account)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained cardholder/funding-account/BIN set so the
  actor + tests run offline."
  []
  {:cardholders
   {"ch-1" {:id "ch-1" :name "田中 一郎" :jurisdiction "JPN" :bin "400000"
            :funding-account-ref "fa-1" :status :intake :card-reference nil
            :card-issued? false :sanctions-hit? false :id-doc "passport-jp-****1234"}
    "ch-2" {:id "ch-2" :name "Nowhere Holdings Cardholder" :jurisdiction "ATL" :bin "999999"
            :funding-account-ref "fa-2" :status :intake :card-reference nil
            :card-issued? false :sanctions-hit? false :id-doc "passport-xx-****0000"}
    "ch-3" {:id "ch-3" :name "J. Doe" :jurisdiction "JPN" :bin "400000"
            :funding-account-ref "fa-1" :status :intake :card-reference nil
            :card-issued? false :sanctions-hit? true :id-doc nil}
    ;; never on the demo BIN sponsorship path by default -- spare
    ;; cardholder for tests that need a :sanctions-hit? false /
    ;; :id-doc nil cardholder (screens to :incomplete, never :clear,
    ;; without also tripping :sanctions-hit).
    "ch-4" {:id "ch-4" :name "鈴木 花子" :jurisdiction "JPN" :bin "400000"
            :funding-account-ref "fa-1" :status :intake :card-reference nil
            :card-issued? false :sanctions-hit? false :id-doc nil}
    ;; linked to fa-2, whose :verified? is false -- a clean, KYC-clearable
    ;; cardholder on an otherwise-sponsorable BIN, used to exercise
    ;; `funding-account-unverified-violations` in isolation (without also
    ;; tripping the ATL/no-spec-basis or bin-not-sponsored checks).
    "ch-5" {:id "ch-5" :name "Unverified Funding Cardholder" :jurisdiction "JPN" :bin "400000"
            :funding-account-ref "fa-2" :status :intake :card-reference nil
            :card-issued? false :sanctions-hit? false :id-doc "passport-jp-****9999"}}
   :bins
   {"400000" {:bin "400000" :jurisdiction "JPN" :scheme "Visa"}
    "999999" {:bin "999999" :jurisdiction "ATL" :scheme "Visa"}}
   :funding-accounts
   {"fa-1" {:id "fa-1" :type :bank-deposit :verified? true
            :available-balance 500000 :daily-limit 100000 :daily-spend-used 0}
    "fa-2" {:id "fa-2" :type :emoney-wallet :verified? false
            :available-balance 1000 :daily-limit 5000 :daily-spend-used 0}
    "fa-3" {:id "fa-3" :type :credit-line :verified? true
            :available-balance nil :credit-limit 200000
            :daily-limit 50000 :daily-spend-used 48000}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- sponsor!
  "Backend-agnostic `:sponsorship/mark-active` -- drafts the BIN/range
  sponsorship record and returns {:result .. :sponsorship ..} for the
  caller to persist."
  [_s bin scheme sponsor-bank range-size jurisdiction]
  (let [result (registry/register-sponsorship scheme sponsor-bank bin range-size jurisdiction)]
    {:result result
     :sponsorship {:scheme scheme :sponsor-bank sponsor-bank :bin bin
                   :range-size range-size :jurisdiction jurisdiction :active? true}}))

(defn- issue!
  "Backend-agnostic `:card/mark-issued` -- looks up the cardholder + its
  funding account via the protocol, drafts the issuance record (assigns
  a synthetic Luhn-valid card reference under the sponsored BIN), and
  returns {:result .. :card-reference .. :cardholder-patch ..}."
  [s cardholder-id]
  (let [ch (cardholder s cardholder-id)
        bin (:bin ch)
        seq-n (next-sequence s bin)
        result (registry/register-card-issuance
                cardholder-id bin seq-n (:funding-account-ref ch) (:jurisdiction ch))]
    {:result result
     :card-reference (get result "card_reference")
     :cardholder-patch {:status :issued
                        :card-reference (get result "card_reference")
                        :card-issued? true}}))

(defn- lifecycle!
  "Backend-agnostic `:card/lifecycle-applied` -- drafts the append-only
  lifecycle-event record and returns {:result .. :cardholder-patch ..}.
  `next-status` is computed by the governor-cleared caller (this fn
  trusts its `event` argument the same way `formation.store`'s
  `amend!`/`dissolve!` trust an already-governor-cleared changed-fields
  map -- `cardissuing.governor`'s `lifecycle-transition-invalid-
  violations` is what actually enforces which transitions are legal)."
  [s cardholder-id event effective-date reason]
  (let [ch (cardholder s cardholder-id)
        card-reference (:card-reference ch)
        result (registry/register-lifecycle-event card-reference event effective-date reason)
        next-status (case event
                      :activate :active
                      :block    :blocked
                      :reissue  :active
                      :close    :closed)]
    (if (= event :reissue)
      (let [seq-n (next-sequence s (:bin ch))
            reissue-result (registry/register-card-issuance
                            cardholder-id (:bin ch) seq-n (:funding-account-ref ch) (:jurisdiction ch))]
        {:result result
         :reissue-result reissue-result
         :cardholder-patch {:status next-status
                            :card-reference (get reissue-result "card_reference")}})
      {:result result
       :cardholder-patch {:status next-status}})))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (cardholder [_ id] (get-in @a [:cardholders id]))
  (all-cardholders [_] (sort-by :id (vals (:cardholders @a))))
  (kyc-of [_ id] (get-in @a [:kyc id]))
  (bin-info [_ bin] (get-in @a [:bins bin]))
  (bin-assessment-of [_ bin] (get-in @a [:bin-assessments bin]))
  (sponsorship-of [_ bin] (get-in @a [:sponsorships bin]))
  (funding-account-of [_ ref] (get-in @a [:funding-accounts ref]))
  (authorization-decision-of [_ tx-id] (get-in @a [:authorization-decisions tx-id]))
  (dispute-of [_ tx-id] (get-in @a [:disputes tx-id]))
  (ledger [_] (:ledger @a))
  (registry-history [_] (:registry @a))
  (next-sequence [_ bin] (get-in @a [:sequences bin] 0))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :cardholder/upsert
      (swap! a update-in [:cardholders (:id value)] merge value)

      :bin-assessment/set
      (swap! a assoc-in [:bin-assessments (first path)] payload)

      :kyc/set
      (swap! a assoc-in [:kyc (first path)] payload)

      :sponsorship/mark-active
      (let [bin (first path)
            {:keys [scheme sponsor-bank range-size jurisdiction]} value
            {:keys [result sponsorship]} (sponsor! s bin scheme sponsor-bank range-size jurisdiction)]
        (swap! a (fn [state]
                   (-> state
                       (assoc-in [:sponsorships bin] sponsorship)
                       (update :registry registry/append result))))
        result)

      :card/mark-issued
      (let [cardholder-id (first path)
            {:keys [result cardholder-patch]} (issue! s cardholder-id)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences (:bin (get-in state [:cardholders cardholder-id]))] (fnil inc 0))
                       (update-in [:cardholders cardholder-id] merge cardholder-patch)
                       (update :registry registry/append result))))
        result)

      :card/lifecycle-applied
      (let [cardholder-id (first path)
            {:keys [event effective-date reason]} value
            {:keys [result reissue-result cardholder-patch]} (lifecycle! s cardholder-id event effective-date reason)]
        (swap! a (fn [state]
                   (cond-> state
                     true (update-in [:cardholders cardholder-id] merge cardholder-patch)
                     true (update :registry registry/append result)
                     reissue-result (update :registry registry/append reissue-result)
                     (= event :reissue) (update-in [:sequences (:bin (get-in state [:cardholders cardholder-id]))] (fnil inc 0)))))
        result)

      :authorization/decision-recorded
      ;; card-reference / funding-account-ref are GROUND TRUTH, derived
      ;; here from the cardholder record (subject, via path) -- never
      ;; trusted from the (advisor-authored) proposal's :value. Only the
      ;; decision itself (:approve/:decline, the actual domain output)
      ;; and the transaction's own :transaction-id/:amount/:mcc come
      ;; from :value.
      (let [cardholder-id (first path)
            ch (cardholder s cardholder-id)
            card-reference (:card-reference ch)
            funding-account-ref (:funding-account-ref ch)
            {:keys [transaction-id decision amount mcc]} value
            result (registry/register-authorization-decision card-reference transaction-id decision amount mcc)]
        (swap! a (fn [state]
                   (cond-> state
                     true (assoc-in [:authorization-decisions transaction-id]
                                    {:transaction-id transaction-id :card-reference card-reference
                                     :decision decision :amount amount :mcc mcc})
                     true (update :registry registry/append result)
                     (= decision :approve)
                     (update-in [:funding-accounts funding-account-ref :daily-spend-used] (fnil + 0) amount))))
        result)

      :dispute/mark-initiated
      (let [transaction-id (first path)
            {:keys [reason effective-date]} value
            result (registry/register-dispute transaction-id reason effective-date)]
        (swap! a (fn [state]
                   (-> state
                       (assoc-in [:disputes transaction-id] {:transaction-id transaction-id :reason reason})
                       (update :registry registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-cardholders [s cardholders] (when (seq cardholders) (swap! a assoc :cardholders cardholders)) s)
  (with-funding-accounts [s accounts] (when (seq accounts) (swap! a assoc :funding-accounts accounts)) s))

(defn seed-db
  "A MemStore seeded with the demo cardholder/funding-account/BIN set.
  The deterministic default."
  []
  (let [{:keys [cardholders bins funding-accounts]} (demo-data)]
    (->MemStore (atom {:cardholders cardholders :bins bins :funding-accounts funding-accounts
                       :bin-assessments {} :sponsorships {} :kyc {}
                       :authorization-decisions {} :disputes {}
                       :ledger [] :sequences {} :registry []}))))
