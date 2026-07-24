(ns cardissuing.governor
  "Card Issuing Governor -- the independent compliance layer that earns
  the Card Issuing Advisor the right to commit. The LLM has no notion of
  card-issuing supervisory law, whether a cardholder's own funding
  account has actually been verified, whether a BIN is actually under an
  active sponsorship agreement, whether a card's own synthetic reference
  actually passes its own Luhn checksum, or when an act stops being a
  draft and becomes a real BIN/range sponsorship commitment, a real card
  issuance, a real card-lifecycle transition, a real authorization
  decision against real funds/credit, or a real dispute/chargeback
  initiation -- so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD, the card-issuing analog of
  `formation.governor`/`banking.governor`/`card.governor`.

  Eighteen checks, in priority order. The first sixteen are HARD
  violations: a human approver CANNOT override them (you don't get to
  approve your way past a fabricated jurisdiction spec-basis, an
  unresolved sanctions hit, an unverified funding account, an
  unsponsored BIN, a checksum-invalid card reference, or a double
  actuation). The last two are SOFT: they ask a human to look (low
  confidence / actuation), and the human may approve -- but see
  `cardissuing.phase`: for `:stake :actuation` (a real BIN sponsorship
  commitment, a real card issuance, a real card-lifecycle transition, a
  real authorization decision, or a real dispute initiation) NO phase
  ever allows auto-commit either. Two independent layers agree that
  actuation is always a human call.

    1. Effect matches op            -- does the proposal's :effect
                                        match the ONE legitimate effect
                                        for the REQUEST's :op
                                        (`op->effect`)? Every check
                                        below keys off the REQUEST's
                                        :op, not the proposal's
                                        self-reported :effect -- without
                                        this check first, an untrusted
                                        advisor could answer a
                                        harmless-looking `:bin/assess`
                                        request with `:effect :card/
                                        mark-issued`, and a human
                                        approving what looks like an
                                        assessment would silently
                                        trigger a REAL card issuance
                                        with none of `:card/issue`'s own
                                        scrutiny ever run.
    2. Spec-basis                   -- did the BIN/scheme sponsorship
                                        proposal cite an OFFICIAL source
                                        (`cardissuing.facts`), or invent
                                        one?
    3. Sanctions hold                -- does the cardholder at stake
                                        carry a sanctions/PEP hit
                                        (screened in THIS proposal, or
                                        already on file)?
    4. KYC incomplete                -- for `:card/issue`, has the
                                        cardholder actually been
                                        screened AND cleared
                                        (`:verdict :clear`)? A
                                        never-screened cardholder is not
                                        a hit (nil != :hit), so
                                        `sanctions-violations` alone
                                        would let a card be issued with
                                        zero screening ever performed.
    5. Funding account unverified    -- for `:card/issue`/
                                        `:authorization/decide`, has the
                                        linked funding account actually
                                        been marked `:verified?`? Do not
                                        trust the advisor's self-reported
                                        confidence alone.
    6. BIN not sponsored             -- for `:card/issue`,
                                        INDEPENDENTLY recompute whether
                                        the cardholder's own BIN is
                                        under an ACTIVE sponsorship
                                        record (`cardissuing.store/
                                        sponsorship-of`) -- needs no
                                        proposal inspection at all.
    7. Card-reference checksum       -- for `:card/lifecycle`/
       invalid                          `:authorization/decide`,
                                        INDEPENDENTLY recompute whether
                                        the cardholder's already-issued
                                        `:card-reference` passes Luhn
                                        (`cardissuing.registry/luhn-
                                        valid?`) -- the SAME
                                        'independently recompute a
                                        ground-truth checksum, never
                                        trust the proposal' discipline
                                        `banking.governor`'s IBAN
                                        MOD-97-10 check and
                                        `formation.registry/validate-lei`
                                        establish, applied to a
                                        different real checksum
                                        algorithm.
    8. Already issued                -- for `:card/issue`, refuses to
                                        issue a SECOND card for the SAME
                                        cardholder, off a dedicated
                                        `:card-issued?` fact (never a
                                        `:status` value).
    9. Already sponsored             -- for `:bin/sponsor`, refuses to
                                        sponsor the SAME BIN twice, off
                                        a dedicated sponsorship
                                        `:active?` fact.
   10. Lifecycle transition invalid  -- for `:card/lifecycle`, is the
                                        proposed `:event` actually a
                                        legal transition from the
                                        cardholder's CURRENT `:status`
                                        (`:activate` only from
                                        `:issued`, `:block` only from
                                        `:active`, `:reissue` only from
                                        `:blocked`, `:close` only from
                                        a non-`:closed` issued state)?
   11. Post-issue intake blocked     -- `:cardholder/intake` exists for
                                        PRE-issuance data entry -- it is
                                        also the ONLY op in ANY phase's
                                        `:auto` set. Once a cardholder's
                                        card has been issued (`:status`
                                        anything past `:intake`),
                                        allowing intake to keep touching
                                        the record would let the funding
                                        link, BIN or even status itself
                                        be silently rewritten with ZERO
                                        governor scrutiny -- a structural
                                        bypass of `:card/lifecycle`'s own
                                        gate.
   12. Intake fabrication            -- even a PRE-issuance intake patch
                                        may never set `:card-reference`
                                        or `:card-issued?`, never set
                                        `:status` to anything but
                                        `:intake` (those are reached
                                        ONLY via a real `:card/issue`/
                                        `:card/lifecycle`), and its own
                                        patch `:id` (if present) must
                                        match the request's `:subject`.
   13. Velocity limit exceeded       -- for `:authorization/decide`
                                        proposing `:approve`,
                                        INDEPENDENTLY recompute whether
                                        `daily-spend-used + amount`
                                        would exceed the funding
                                        account's own `:daily-limit`.
   14. MCC restricted                -- for `:authorization/decide`
                                        proposing `:approve`, is the
                                        transaction's `:mcc` in
                                        `cardissuing.facts/default-
                                        restricted-mccs`?
   15. Insufficient funds/credit     -- for `:authorization/decide`
                                        proposing `:approve`,
                                        INDEPENDENTLY recompute whether
                                        the funding account's own
                                        available balance (bank deposit/
                                        e-money wallet) or remaining
                                        credit (credit line) actually
                                        covers `amount`.
   16. Dispute target invalid /
       already-decided/-disputed     -- for `:authorization/decide`, the
                                        SAME `transaction-id` cannot be
                                        decided twice. For `:dispute/
                                        initiate`, the target
                                        `transaction-id` must have an
                                        existing `:approve` authorization
                                        decision on file, and must not
                                        already carry a dispute.
   17. Confidence floor              -- LLM confidence below threshold
                                        -> escalate.
   18. Actuation gate                -- `:stake :actuation` -> always
                                        escalate; never auto, at any
                                        phase (structural, not a policy
                                        toggle)."
  (:require [cardissuing.facts :as facts]
            [cardissuing.registry :as registry]
            [cardissuing.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  :actuation = a real BIN/range sponsorship commitment, a real card
  issuance, a real card-lifecycle transition, a real issuer
  authorization decision against real funds/credit, or a real dispute/
  chargeback initiation. There is exactly one member on purpose:
  actuation is not a spectrum."
  #{:actuation})

;; ----------------------------- checks -----------------------------

(def op->effect
  "The ONE legitimate `:effect` a proposal may declare for each op --
  `cardissuing.operation/commit-record` takes `:effect` straight from
  the (untrusted) advisor proposal with no cross-check of its own, so
  this table is the only thing standing between 'the request says
  `:bin/assess`' and 'the SSoT mutation that actually runs is `:card/
  mark-issued`'. Every other check in this namespace keys off the
  REQUEST's :op -- so a mismatched :effect would let all of THEIR
  scrutiny run against the wrong (lower-stakes) op while a different,
  possibly far higher-stakes effect gets committed."
  {:cardholder/intake    :cardholder/upsert
   :bin/assess           :bin-assessment/set
   :kyc/screen           :kyc/set
   :bin/sponsor          :sponsorship/mark-active
   :card/issue           :card/mark-issued
   :card/lifecycle       :card/lifecycle-applied
   :authorization/decide :authorization/decision-recorded
   :dispute/initiate     :dispute/mark-initiated})

(defn- effect-mismatch-violations
  [{:keys [op]} proposal]
  (when-let [expected (op->effect op)]
    (when (not= expected (:effect proposal))
      [{:rule :effect-mismatch
        :detail (str "op " op " の提案は :effect " expected
                     " のはずが実際には " (:effect proposal) " になっている")}])))

(defn- spec-basis-violations
  [{:keys [op]} proposal]
  (when (contains? #{:bin/assess :bin/sponsor} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案はBIN/レンジ・スポンサーシップ要件として扱えない"}]))))

(defn- sanctions-violations
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :hit (get-in proposal [:value :verdict]))
        cardholder-id (when (contains? #{:kyc/screen :card/issue} op) subject)
        hit-on-file? (and cardholder-id (= :hit (:verdict (store/kyc-of st cardholder-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :sanctions-hit
        :detail "制裁/PEPリスト一致のあるカード会員へのカード発行は進められない"}])))

(defn- kyc-incomplete-violations
  [{:keys [op subject]} st]
  (when (= op :card/issue)
    (when-not (= :clear (:verdict (store/kyc-of st subject)))
      [{:rule :kyc-incomplete
        :detail "カード会員のKYCスクリーニング(:clear)が完了していない状態でのカード発行提案"}])))

(defn- funding-account-unverified-violations
  [{:keys [op subject]} st]
  (when (contains? #{:card/issue :authorization/decide} op)
    (let [ch (store/cardholder st subject)
          fa (store/funding-account-of st (:funding-account-ref ch))]
      (when-not (:verified? fa)
        [{:rule :funding-account-unverified
          :detail "資金源(funding account)が検証済みでない状態でのカード発行/オーソリ判断提案"}]))))

(defn- bin-not-sponsored-violations
  [{:keys [op subject]} st]
  (when (= op :card/issue)
    (let [ch (store/cardholder st subject)
          sponsorship (store/sponsorship-of st (:bin ch))]
      (when-not (:active? sponsorship)
        [{:rule :bin-not-sponsored
          :detail (str (:bin ch) " は有効なBIN/レンジ・スポンサーシップ下にない")}]))))

(defn- card-reference-checksum-invalid-violations
  [{:keys [op subject]} st]
  (when (contains? #{:card/lifecycle :authorization/decide} op)
    (let [ch (store/cardholder st subject)
          card-reference (:card-reference ch)]
      (when (and card-reference (not (registry/luhn-valid? card-reference)))
        [{:rule :card-reference-checksum-invalid
          :detail (str subject " のcard-reference(" (registry/masked card-reference) ")がLuhn検査に不合格")}]))))

(defn- already-issued-violations
  [{:keys [op subject]} st]
  (when (= op :card/issue)
    (when (:card-issued? (store/cardholder st subject))
      [{:rule :already-issued
        :detail (str subject " には既にカードが発行済み")}])))

(defn- already-sponsored-violations
  [{:keys [op subject]} st]
  (when (= op :bin/sponsor)
    (when (:active? (store/sponsorship-of st subject))
      [{:rule :already-sponsored
        :detail (str subject " は既にスポンサーシップ締結済み")}])))

(def ^:private legal-predecessor
  "event -> the ONE cardholder :status a card must be in for that
  lifecycle event to be legal. An allowlist, not a denylist, so a
  future event defaults to illegal until deliberately wired here."
  {:activate :issued
   :block    :active
   :reissue  :blocked
   :close    #{:issued :active :blocked}})

(defn- lifecycle-transition-invalid-violations
  [{:keys [op subject]} proposal st]
  (when (= op :card/lifecycle)
    (let [event (get-in proposal [:value :event])
          current (:status (store/cardholder st subject))
          allowed (get legal-predecessor event)
          ok? (if (set? allowed) (contains? allowed current) (= allowed current))]
      (when-not ok?
        [{:rule :lifecycle-transition-invalid
          :detail (str subject " の現在の状態 " current " から " event " への遷移は許可されない")}]))))

(defn- post-issue-intake-violations
  [{:keys [op subject]} st]
  (when (= op :cardholder/intake)
    (let [ch (store/cardholder st subject)]
      (when (not= :intake (:status ch))
        [{:rule :post-issue-intake-blocked
          :detail "カード発行後のカード会員レコードへのintake経由の変更は禁止。:card/lifecycle を使うこと"}]))))

(defn- intake-fabrication-violations
  [{:keys [op subject]} proposal]
  (when (= op :cardholder/intake)
    (let [patch (:value proposal)]
      (cond-> []
        (some #(contains? patch %) [:card-reference :card-issued?])
        (conj {:rule :intake-forbidden-field
               :detail "intake で card-reference/card-issued? を設定することはできない（実際の card/issue でのみ発行される）"})
        (and (contains? patch :status) (not= :intake (:status patch)))
        (conj {:rule :intake-forbidden-status
               :detail "intake で :status を :intake 以外にすることはできない（実際の card/issue や card/lifecycle でのみ到達する状態）"})
        (and (contains? patch :id) (not= (:id patch) subject))
        (conj {:rule :intake-subject-mismatch
               :detail "patch の :id がリクエストの subject と一致しない"})))))

(defn- velocity-limit-exceeded-violations
  [{:keys [op subject]} proposal st]
  (when (= op :authorization/decide)
    (when (= :approve (get-in proposal [:value :decision]))
      (let [ch (store/cardholder st subject)
            fa (store/funding-account-of st (:funding-account-ref ch))
            amount (get-in proposal [:value :amount] 0)
            used (:daily-spend-used fa 0)
            limit (:daily-limit fa)]
        (when (and limit (> (+ used amount) limit))
          [{:rule :velocity-limit-exceeded
            :detail (str "1日あたりの利用限度額(" limit ")を超過する提案: 既存利用額" used " + 今回" amount)}])))))

(defn- mcc-restricted-violations
  [{:keys [op]} proposal]
  (when (= op :authorization/decide)
    (when (= :approve (get-in proposal [:value :decision]))
      (let [mcc (get-in proposal [:value :mcc])]
        (when (contains? facts/default-restricted-mccs mcc)
          [{:rule :mcc-restricted
            :detail (str "制限対象のmerchant category code(" mcc ")での承認提案")}])))))

(defn- insufficient-funds-violations
  [{:keys [op subject]} proposal st]
  (when (= op :authorization/decide)
    (when (= :approve (get-in proposal [:value :decision]))
      (let [ch (store/cardholder st subject)
            fa (store/funding-account-of st (:funding-account-ref ch))
            amount (get-in proposal [:value :amount] 0)
            available (if (:credit-limit fa)
                        (- (:credit-limit fa) (:daily-spend-used fa 0))
                        (:available-balance fa 0))]
        (when (> amount available)
          [{:rule :insufficient-funds
            :detail (str "資金源の残高/与信枠(" available ")を超過する承認提案(" amount ")")}])))))

(defn- already-decided-violations
  [{:keys [op]} proposal st]
  (when (= op :authorization/decide)
    (let [tx-id (get-in proposal [:value :transaction-id])]
      (when (store/authorization-decision-of st tx-id)
        [{:rule :already-decided
          :detail (str tx-id " は既にオーソリ判断済み")}]))))

(defn- dispute-target-violations
  [{:keys [op subject]} st]
  (when (= op :dispute/initiate)
    (let [decision (store/authorization-decision-of st subject)]
      (cond-> []
        (not= :approve (:decision decision))
        (conj {:rule :dispute-target-invalid
               :detail (str subject " には承認済みのオーソリ判断が無く、ディスピュートの対象にできない")})
        (store/dispute-of st subject)
        (conj {:rule :already-disputed
               :detail (str subject " は既にディスピュート起票済み")})))))

(defn check
  "Censors a Card Issuing Advisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (effect-mismatch-violations request proposal)
                           (spec-basis-violations request proposal)
                           (sanctions-violations request proposal st)
                           (kyc-incomplete-violations request st)
                           (funding-account-unverified-violations request st)
                           (bin-not-sponsored-violations request st)
                           (card-reference-checksum-invalid-violations request st)
                           (already-issued-violations request st)
                           (already-sponsored-violations request st)
                           (lifecycle-transition-invalid-violations request proposal st)
                           (post-issue-intake-violations request st)
                           (intake-fabrication-violations request proposal)
                           (velocity-limit-exceeded-violations request proposal st)
                           (mcc-restricted-violations request proposal)
                           (insufficient-funds-violations request proposal st)
                           (already-decided-violations request proposal st)
                           (dispute-target-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
