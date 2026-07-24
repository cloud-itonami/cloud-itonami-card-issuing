(ns cardissuing.registry
  "Pure-function registry-record construction for the issuer side of the
  card network: BIN/range sponsorship drafts, card-issuance drafts (with
  a real Luhn (ISO/IEC 7812-1 mod-10) check-digit computation over a
  SYNTHETIC card-reference identifier -- this actor never handles a real
  network-issued PAN, see README `Scope`), card-lifecycle-event drafts,
  authorization-decision records, and issuer-side dispute/chargeback-
  initiation drafts.

  The Luhn arithmetic is the same 'independently recomputable ground-
  truth check' discipline `banking.registry/iban-checksum-invalid?`
  (ISO 7064 MOD 97-10) and `formation.registry/validate-lei` (ISO 7064
  MOD 97-10) already establish in this fleet: `cardissuing.governor`
  recomputes `luhn-valid?` over the CONSTRUCTED card reference itself
  (never trusts an advisor's self-reported checksum), the same
  discipline applied to a different real checksum algorithm.

  This namespace is pure data + pure functions -- no I/O, no network call
  to any card scheme or government registry. It builds the RECORD an
  issuer would keep, not the act of sponsoring a BIN / issuing a card
  itself (that is `cardissuing.operation`'s `:bin/sponsor` / `:card/issue`,
  always human-gated -- see README).")

;; -- Luhn (ISO/IEC 7812-1 mod-10) --

(defn- digit [ch]
  (- (int ch) (int \0)))

(defn luhn-check-digit
  "The Luhn check digit for a string of decimal digits (the payload,
  WITHOUT its own check digit) -- doubles every digit from the
  rightmost payload digit, alternating, summing digits-of-digits, and
  returns the digit that makes the total (with check digit appended)
  a multiple of 10."
  [payload]
  (when-not (re-matches #"[0-9]+" payload)
    (throw (ex-info (str "luhn-check-digit: payload must be all-digits, got " (pr-str payload)) {})))
  (let [digits (mapv digit payload)
        n (count digits)
        sum (reduce +
                    (map-indexed
                     (fn [i d]
                       ;; position counted from the RIGHT of the payload;
                       ;; double every digit that will end up in an
                       ;; odd position (1-indexed from the right) of the
                       ;; final number-with-check-digit appended.
                       (let [pos-from-right (- n i)]
                         (if (odd? pos-from-right)
                           (let [doubled (* 2 d)]
                             (if (> doubled 9) (- doubled 9) doubled))
                           d)))
                     digits))]
    (mod (- 10 (mod sum 10)) 10)))

(defn luhn-valid?
  "Does `full-number` (payload + its own check digit, all-digits string)
  pass the Luhn (mod-10) checksum?"
  [full-number]
  (if (or (not (string? full-number)) (not (re-matches #"[0-9]+" full-number)) (< (count full-number) 2))
    false
    (let [payload (subs full-number 0 (dec (count full-number)))
          check (digit (last full-number))]
      (= check (luhn-check-digit payload)))))

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn assign-card-reference
  "Build a SYNTHETIC 16-digit card-reference identifier for this actor's
  own draft record: a 6-digit `bin` + a 9-digit zero-padded `sequence` +
  1 Luhn check digit. This is NEVER a real network-issued PAN -- it is a
  non-production identifier this actor's own registry constructs to
  prove its draft records are internally consistent (same posture as
  `formation.registry/assign-lei` constructing a draft LEI). Real PAN
  assignment/tokenization is the card scheme's + operator's own
  infrastructure, out of scope for this actor (see README `Scope`)."
  [bin sequence]
  (when (not= (count bin) 6)
    (throw (ex-info "cardissuing.registry: BIN must be 6 digits" {:bin bin})))
  (when-not (re-matches #"[0-9]{6}" bin)
    (throw (ex-info "cardissuing.registry: BIN must be all-digits" {:bin bin})))
  (when (< sequence 0)
    (throw (ex-info "cardissuing.registry: sequence must be >= 0" {:sequence sequence})))
  (let [payload (str bin (zero-pad sequence 9))
        check (luhn-check-digit payload)]
    (str payload check)))

(defn masked
  "Never persist/log/print a full card reference outside the actor's own
  draft record -- callers that need a human-facing summary use this
  (BIN + last 4, the same masking convention real card statements use)."
  [card-reference]
  (when (and card-reference (>= (count card-reference) 10))
    (str (subs card-reference 0 6) "******" (subs card-reference (- (count card-reference) 4)))))

;; -- registry records --

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  card scheme's / sponsor bank's own act, not this actor's. See README
  `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_scheme" false
   "status" "draft-unsigned"})

(defn register-sponsorship
  "Validate + construct a BIN/range sponsorship agreement DRAFT. Pure
  function -- does not touch any real card scheme or sponsor bank."
  [scheme sponsor-bank bin range-size jurisdiction]
  (when-not (and scheme (not= scheme ""))
    (throw (ex-info "sponsorship: scheme required" {})))
  (when-not (and sponsor-bank (not= sponsor-bank ""))
    (throw (ex-info "sponsorship: sponsor-bank required" {})))
  (when-not (re-matches #"[0-9]{6}" (str bin))
    (throw (ex-info "sponsorship: bin must be 6 digits" {})))
  (when (< range-size 1)
    (throw (ex-info "sponsorship: range-size must be >= 1" {})))
  (let [record-id (str "SPON-" bin)
        record {"record_id" record-id
                 "kind" "sponsorship-draft"
                 "scheme" scheme
                 "sponsor_bank" sponsor-bank
                 "bin" bin
                 "range_size" range-size
                 "jurisdiction" jurisdiction
                 "immutable" true}]
    {"record" record "record_id" record-id
     "certificate" (unsigned-certificate "BinSponsorshipCertificate" bin record-id)}))

(defn register-card-issuance
  "Validate + construct a card-issuance DRAFT, assigning a synthetic Luhn-
  valid card reference under the sponsored `bin`. Pure function -- does
  not touch any real card scheme."
  [cardholder-id bin sequence funding-account-ref jurisdiction]
  (when-not (and cardholder-id (not= cardholder-id ""))
    (throw (ex-info "issuance: cardholder-id required" {})))
  (when-not (and funding-account-ref (not= funding-account-ref ""))
    (throw (ex-info "issuance: funding-account-ref required" {})))
  (let [card-reference (assign-card-reference bin sequence)
        record {"record_id" card-reference
                 "kind" "issuance-draft"
                 "cardholder_id" cardholder-id
                 "bin" bin
                 "funding_account_ref" funding-account-ref
                 "jurisdiction" jurisdiction
                 "immutable" true}]
    {"record" record "card_reference" card-reference
     "certificate" (unsigned-certificate "CardIssuanceCertificate" cardholder-id card-reference)}))

(defn register-lifecycle-event
  "Append-only card-lifecycle-event draft (activate/block/reissue/close).
  Never overwrites the issuance record."
  [card-reference event effective-date reason]
  (when-not (and card-reference (not= card-reference ""))
    (throw (ex-info "lifecycle: card-reference required" {})))
  (when-not (contains? #{:activate :block :reissue :close} event)
    (throw (ex-info "lifecycle: event must be one of :activate/:block/:reissue/:close" {:event event})))
  {"record" {"record_id" (str card-reference "#" (name event) "@" effective-date)
             "kind" "lifecycle-draft"
             "card_reference" card-reference
             "event" (name event)
             "reason" reason
             "effective_date" effective-date
             "immutable" true}})

(defn register-authorization-decision
  "Append-only issuer authorization-decision record (approve/decline)
  against a real-time transaction request. Never overwrites a prior
  decision -- each transaction-id gets exactly one decision record."
  [card-reference transaction-id decision amount mcc]
  (when-not (contains? #{:approve :decline} decision)
    (throw (ex-info "authorization: decision must be :approve or :decline" {:decision decision})))
  {"record" {"record_id" (str "AUTH-" transaction-id)
             "kind" "authorization-decision"
             "card_reference" card-reference
             "transaction_id" transaction-id
             "decision" (name decision)
             "amount" amount
             "mcc" mcc
             "immutable" true}})

(defn register-dispute
  "Append-only issuer-side dispute/chargeback-initiation draft -- the
  cardholder-facing side, distinct from the acquirer-side chargeback
  handling `cloud-itonami-isic-6619` performs on the merchant side of the
  same card network."
  [transaction-id reason effective-date]
  (when-not (and transaction-id (not= transaction-id ""))
    (throw (ex-info "dispute: transaction-id required" {})))
  (when-not (and reason (not= reason ""))
    (throw (ex-info "dispute: reason required" {})))
  {"record" {"record_id" (str "DISP-" transaction-id "@" effective-date)
             "kind" "dispute-draft"
             "transaction_id" transaction-id
             "reason" reason
             "effective_date" effective-date
             "immutable" true}})

(defn append
  "Append a registry record, returning a NEW list (never mutate history in place)."
  [history result]
  (conj (vec history) (get result "record")))
