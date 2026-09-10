(ns cardissuing.operation
  "OperationActor -- one card-issuing operation = one supervised actor
  run, expressed as a langgraph-clj StateGraph. The advisor (Card
  Issuing Advisor) is sealed into a single node (:advise); its proposal
  is ALWAYS routed through the Card Issuing Governor (:govern) and the
  rollout phase gate (:decide) before anything commits to the SSoT.

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (MemStore today; Datomic/kotoba-server is the next seam) - `store` arg
    - the Advisor  (mock | real LLM)                                       - :advisor opt
    - the Phase    (0->3 rollout)                                          - :phase in ctx

  One graph run = one card-issuing operation (intake -> advise -> govern
  -> decide -> commit | hold | approval). No unbounded inner loop -- each
  operation is auditable and checkpointed.

  Human-in-the-loop = real approval workflow:
  `interrupt-before #{:request-approval}` pauses the actor and hands the
  decision to a human operator (the licensed card-issuing operator's
  risk/compliance reviewer). The approver resumes with
  `{:approval {:status :approved}}` (or :rejected). `:bin/sponsor`,
  `:card/issue`, `:card/lifecycle`, `:authorization/decide` and
  `:dispute/initiate` ALWAYS reach this node when the governor is clean
  -- see `cardissuing.phase`."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [cardissuing.cardissuingadvisor :as cardissuingadvisor]
            [cardissuing.governor :as governor]
            [cardissuing.phase :as phase]
            [cardissuing.store :as store]))

(defn- commit-fact
  "The ledger fact for a committed op. `approval` is the resumed
  human-approval decision when this commit came via :request-approval
  (nil for an auto-commit, e.g. phase 3's :cardholder/intake) -- without
  this, the ledger cannot answer 'approved by whom', despite
  `cardissuing.store`'s own docstring promising exactly that query."
  [request context proposal approval]
  {:t          :committed
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :commit
   :basis      (:cites proposal)
   :summary    (:summary proposal)
   :approved-by (:by approval)})

(defn- commit-record [request _context proposal]
  {:effect  (:effect proposal)
   :path    [(:subject request)]
   :value   (or (:value proposal) {})
   :payload (:value proposal)})

(defn build
  "Compiles an OperationActor graph bound to `store` (any
  `cardissuing.store/Store`).
  opts:
    :advisor      -- a `cardissuing.cardissuingadvisor/Advisor` (default: mock-advisor)
    :checkpointer -- langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (cardissuingadvisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; injected actor-id/role/phase
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}   ; :commit | :hold | :escalate
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; Card Issuing Advisor inference (the contained intelligence node) -- proposal only.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (cardissuingadvisor/-advise advisor store request)]
            {:proposal p :audit [(cardissuingadvisor/trace request p)]})))

      ;; Card Issuing Governor -- independent censor (separate system than the LLM).
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal store)}))

      ;; Decide: governor disposition, then the rollout-phase gate (which can
      ;; only add caution). HARD governor violations -> HOLD (no override).
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (governor/hold-fact request context verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested
                        :op (:op request) :subject (:subject request)
                        :reason (or reason
                                    (cond (:high-stakes? verdict) :actuation
                                          :else :low-confidence))
                        :phase ph
                        :confidence (:confidence verdict)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal)}))))

      ;; Approval handoff -- paused by interrupt-before; a human operator
      ;; resumes with :approval. Then route commit/hold.
      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (commit-record request context proposal)
                            :payload (assoc (:value proposal)
                                            :approved-by (:by approval)))
             :audit [{:t :approval-granted :op (:op request)
                      :subject (:subject request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (governor/hold-fact request context
                                                (assoc verdict :violations
                                                       [{:rule :approver-rejected}]))
                            {:t :approval-rejected})]})))

      ;; Commit -- the ONLY node that writes the SSoT + audit ledger.
      (g/add-node :commit
        (fn [{:keys [request context proposal record approval]}]
          (store/commit-record! store record)
          (let [f (commit-fact request context proposal approval)]
            (store/append-ledger! store f)
            {:audit [f]})))

      ;; Hold -- write the rejection to the ledger; no SSoT mutation.
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
