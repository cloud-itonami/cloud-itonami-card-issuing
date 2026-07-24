(ns cardissuing.phase
  "Phase 0->3 staged rollout -- the card-issuing analog of
  `formation.phase`/`banking.phase`'s ODD-style rollout: start narrow
  (read-only), widen as trust grows. Where `cardissuing.governor`
  answers 'is this allowed?', the phase answers 'how much autonomy does
  the actor have *yet*?'. It can only ever make the actor MORE
  conservative than the governor, never the reverse.

    Phase 0  read-only        -- coverage reads only (still governor-
                                 gated). Shadow/observe.
    Phase 1  assisted-intake  -- cardholder intake allowed, every write
                                 needs human approval.
    Phase 2  + assess/screen  -- adds BIN assessment + KYC screening
                                 writes (still approval).
    Phase 3  supervised auto  -- governor-clean, high-confidence INTAKE
                                 writes may auto-commit. BIN assessment
                                 and KYC screening still escalate (a
                                 human should see a jurisdiction/
                                 cardholder determination before it
                                 becomes the basis for a real BIN
                                 sponsorship or a real card issuance).

  `:bin/sponsor`, `:card/issue`, `:card/lifecycle`, `:authorization/
  decide` and `:dispute/initiate` are deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- this is a permanent
  structural fact about this table, not a rollout milestone still to
  come. A real BIN/range sponsorship commitment, a real card issuance, a
  real card-lifecycle transition, a real issuer authorization decision
  against real funds/credit, or a real dispute/chargeback initiation is
  always a human call; see README `Actuation`. `cardissuing.governor`'s
  `:actuation` high-stakes gate enforces the same invariant
  independently -- two layers, not one, agree on this.")

(def read-ops  #{:coverage/report})
(def write-ops #{:cardholder/intake :bin/assess :kyc/screen
                 :bin/sponsor :card/issue :card/lifecycle
                 :authorization/decide :dispute/initiate})

;; NOTE the invariant: :bin/sponsor, :card/issue, :card/lifecycle,
;; :authorization/decide and :dispute/initiate are members of
;; `write-ops` (they are governor-gated like any write) but are NEVER a
;; member of any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                              :auto #{}}
   1 {:label "assisted-intake" :writes #{:cardholder/intake}                            :auto #{}}
   2 {:label "assisted-assess" :writes #{:cardholder/intake :bin/assess :kyc/screen}     :auto #{}}
   3 {:label "supervised-auto" :writes write-ops                                        :auto #{:cardholder/intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - reads pass through unchanged (phase restricts autonomy, not reads).
  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - the five actuation ops are never auto-eligible at any phase, so they
    always escalate once the governor clears them (or hold if the
    governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Card Issuing Governor verdict to a base disposition before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
