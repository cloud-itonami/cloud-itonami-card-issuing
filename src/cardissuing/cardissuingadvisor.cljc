(ns cardissuing.cardissuingadvisor
  "Card Issuing Advisor client -- the *contained intelligence node*.

  It normalizes cardholder intake, drafts a per-BIN/scheme sponsorship
  requirement checklist, screens cardholders against a KYC/sanctions
  signal, drafts BIN/range sponsorship commitments, card-issuance
  proposals, card-lifecycle transitions (activate/block/reissue/close),
  real-time issuer authorization decisions (approve/decline), and
  issuer-side dispute/chargeback-initiation drafts. CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a rationale
  + the fields it cited), never a committed record or a real-world
  actuation. Every output is censored downstream by
  `cardissuing.governor` before anything touches the SSoT, and
  `:bin/sponsor` / `:card/issue` / `:card/lifecycle` / `:authorization/
  decide` / `:dispute/initiate` proposals NEVER auto-commit at any
  phase -- see README `Actuation`.

  Like `formation.registrarllm`/`banking.bankingadvisor`, this is a
  deterministic mock so the actor graph runs offline and the governor
  contract is exercised end-to-end. In production this calls a real LLM
  (kotoba-llm or equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation if it touches a real BIN
                                 ; sponsorship / card issuance / card
                                 ; lifecycle / authorization decision /
                                 ; dispute initiation
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [cardissuing.facts :as facts]
            [cardissuing.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Cardholder directory upsert -- the LLM only normalizes/validates the
  patch; it does not invent a funding-account link or a BIN. High
  confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "カード会員レコード更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :cardholder/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-bin
  "Per-BIN/scheme sponsorship requirement checklist draft. `:no-spec?`
  injects the failure mode we must defend against: proposing a checklist
  for a jurisdiction with NO official spec-basis in `cardissuing.facts`
  -- the Card Issuing Governor must reject this (never invent a
  country's card-issuing supervisory law)."
  [db {:keys [subject no-spec?]}]
  (let [bi (store/bin-info db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction bi))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "cardissuing.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :bin-assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向けBIN/レンジ・スポンサーシップ要件 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :bin-assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-kyc
  "KYC / sanctions screening draft. `:sanctions-hit?` on the cardholder
  record injects the failure mode: the Card Issuing Governor must HOLD,
  un-overridably, on any sanctions/PEP hit. Missing identification
  yields low confidence -> escalate rather than auto-clear."
  [db {:keys [subject]}]
  (let [ch (store/cardholder db subject)]
    (cond
      (nil? ch)
      {:summary "対象カード会員が見つかりません" :rationale "no cardholder record"
       :cites [] :effect :kyc/set :value {:cardholder-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (:sanctions-hit? ch)
      {:summary    (str (:name ch) ": 制裁/PEPリストと一致")
       :rationale  "スクリーニングが一致を検出。人手確認とホールドが必須。"
       :cites      [:sanctions-list]
       :effect     :kyc/set
       :value      {:cardholder-id subject :verdict :hit}
       :stake      nil
       :confidence 0.95}

      (nil? (:id-doc ch))
      {:summary    (str (:name ch) ": 本人確認書類が未提出")
       :rationale  "本人確認書類が無いため確信度を上げられない。"
       :cites      [:id-doc]
       :effect     :kyc/set
       :value      {:cardholder-id subject :verdict :incomplete}
       :stake      nil
       :confidence 0.4}

      :else
      {:summary    (str (:name ch) ": 制裁リスト非一致、本人確認書類あり")
       :rationale  "本人確認書類確認 + 制裁リスト非一致。"
       :cites      [:id-doc :sanctions-list]
       :effect     :kyc/set
       :value      {:cardholder-id subject :verdict :clear}
       :stake      nil
       :confidence 0.9})))

(defn- propose-sponsorship
  "Draft the actual BIN/range sponsorship-agreement commitment with the
  card scheme + sponsor bank. ALWAYS `:stake :actuation` -- this is a
  REAL-WORLD act (a real sponsorship agreement, real regulatory
  exposure), never a draft the actor may auto-run."
  [db {:keys [subject sponsor-bank range-size]}]
  (let [bi (store/bin-info db subject)
        assessment (store/bin-assessment-of db subject)]
    (if (and assessment (:spec-basis assessment))
      {:summary    (str subject " (" (:scheme bi) ", " (:jurisdiction bi) ") のBIN/レンジ・スポンサーシップ締結案")
       :rationale  (str "spec-basis: " (:spec-basis assessment))
       :cites      [(:spec-basis assessment)]
       :effect     :sponsorship/mark-active
       :value      {:scheme (:scheme bi) :sponsor-bank sponsor-bank
                    :range-size (or range-size 1000) :jurisdiction (:jurisdiction bi)}
       :stake      :actuation
       :confidence 0.9}
      {:summary    (str subject " はスポンサーシップ締結できません")
       :rationale  "BIN/スキーム要件アセスメントが未実施、またはspec-basis無し"
       :cites      []
       :effect     :sponsorship/mark-active
       :value      {:scheme (:scheme bi) :sponsor-bank sponsor-bank
                    :range-size (or range-size 1000) :jurisdiction (:jurisdiction bi)}
       :stake      :actuation
       :confidence 0.2})))

(defn- propose-issuance
  "Draft the actual card-issuance action against a KYC-cleared,
  funding-account-verified cardholder under a sponsored BIN. ALWAYS
  `:stake :actuation`."
  [db {:keys [subject]}]
  (let [ch (store/cardholder db subject)
        kyc (store/kyc-of db subject)
        fa (store/funding-account-of db (:funding-account-ref ch))
        sponsorship (store/sponsorship-of db (:bin ch))
        ready? (and (= :clear (:verdict kyc)) (:verified? fa) (:active? sponsorship))]
    {:summary    (str (:name ch) " へのカード発行準備" (if ready? "完了" (str "未完了")))
     :rationale  (str "KYC: " (:verdict kyc) " / funding-account verified?: " (:verified? fa)
                      " / BIN sponsored?: " (:active? sponsorship))
     :cites      (cond-> [] (:active? sponsorship) (conj (:bin ch)))
     :effect     :card/mark-issued
     :value      {:cardholder-id subject}
     :stake      :actuation
     :confidence (if ready? 0.9 0.3)}))

(defn- propose-lifecycle
  "Draft a card-lifecycle transition (activate/block/reissue/close).
  ALWAYS `:stake :actuation` -- each of these is a real-world change to
  a card's usability against real funds."
  [db {:keys [subject event effective-date reason]}]
  (let [ch (store/cardholder db subject)]
    {:summary    (str (:name ch) " のカード(" (:card-reference ch) ") を " (name event) " する提案")
     :rationale  (str "現在の状態: " (:status ch))
     :cites      []
     :effect     :card/lifecycle-applied
     :value      {:event event :effective-date effective-date :reason reason}
     :stake      :actuation
     :confidence 0.9}))

(defn- decide-authorization
  "Draft a real-time issuer authorization decision (approve/decline)
  against a transaction. The advisor makes a BEST-EFFORT recommendation
  from the same facts the governor will independently re-verify
  (velocity/MCC/balance) -- the governor's own recompute is what
  actually enforces the decision, never this proposal alone. ALWAYS
  `:stake :actuation`."
  [db {:keys [subject transaction-id amount mcc]}]
  (let [ch (store/cardholder db subject)
        fa (store/funding-account-of db (:funding-account-ref ch))
        used (:daily-spend-used fa 0)
        limit (:daily-limit fa)
        available (if (:credit-limit fa) (- (:credit-limit fa) used) (:available-balance fa 0))
        within-velocity? (or (nil? limit) (<= (+ used amount) limit))
        within-funds? (<= amount available)
        not-restricted-mcc? (not (contains? facts/default-restricted-mccs mcc))
        approve? (and within-velocity? within-funds? not-restricted-mcc?
                      (= :active (:status ch)))]
    {:summary    (str transaction-id " (" amount ", MCC " mcc "): "
                      (if approve? "承認" "拒否") "を提案")
     :rationale  (str "velocity ok?: " within-velocity? " / funds ok?: " within-funds?
                      " / mcc restricted?: " (not not-restricted-mcc?)
                      " / card active?: " (= :active (:status ch)))
     :cites      []
     :effect     :authorization/decision-recorded
     :value      {:transaction-id transaction-id :decision (if approve? :approve :decline)
                  :amount amount :mcc mcc}
     :stake      :actuation
     :confidence (if approve? 0.9 0.7)}))

(defn- propose-dispute
  "Draft an issuer-side dispute/chargeback initiation for a settled
  transaction the cardholder disputes. ALWAYS `:stake :actuation` --
  submitted to the same real card scheme dispute process as the original
  authorization."
  [db {:keys [subject reason effective-date]}]
  (let [decision (store/authorization-decision-of db subject)]
    (if (= :approve (:decision decision))
      {:summary    (str subject " のディスピュート起票案: " reason)
       :rationale  "既存の承認済みオーソリ判断記録への追記型ディスピュート"
       :cites      [subject]
       :effect     :dispute/mark-initiated
       :value      {:transaction-id subject :reason reason :effective-date effective-date}
       :stake      :actuation
       :confidence 0.9}
      {:summary    (str subject " はディスピュート起票できません")
       :rationale  "承認済みのオーソリ判断記録が無い"
       :cites      []
       :effect     :dispute/mark-initiated
       :value      {:transaction-id subject :reason reason :effective-date effective-date}
       :stake      :actuation
       :confidence 0.2})))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :cardholder/intake    (normalize-intake db request)
    :bin/assess           (assess-bin db request)
    :kyc/screen           (screen-kyc db request)
    :bin/sponsor          (propose-sponsorship db request)
    :card/issue            (propose-issuance db request)
    :card/lifecycle        (propose-lifecycle db request)
    :authorization/decide  (decide-authorization db request)
    :dispute/initiate      (propose-dispute db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  []
  (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたはカード発行(issuer)プログラム管理エージェントの助言者です。与えられた"
       "事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは一切書かず、"
       "EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:cardholder/upsert|:bin-assessment/set|:kyc/set|:sponsorship/mark-active|"
       ":card/mark-issued|:card/lifecycle-applied|:authorization/decision-recorded|"
       ":dispute/mark-initiated) "
       ":stake(:actuation か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"
       "生のPAN(カード番号)を扱う提案は絶対に行わないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :bin/assess           {:bin-info (store/bin-info st subject)}
    :kyc/screen            {:cardholder (store/cardholder st subject)}
    :card/issue             {:cardholder (store/cardholder st subject)
                             :kyc (store/kyc-of st subject)}
    :bin/sponsor           {:bin-info (store/bin-info st subject)
                            :assessment (store/bin-assessment-of st subject)}
    :card/lifecycle         {:cardholder (store/cardholder st subject)}
    :authorization/decide   {:cardholder (store/cardholder st subject)}
    :dispute/initiate       {:decision (store/authorization-decision-of st subject)}
    {:cardholder (store/cardholder st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure yields
  a safe low-confidence noop so the Card Issuing Governor escalates/holds --
  an LLM hiccup can never auto-sponsor, auto-issue or auto-authorize."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :cardissuingadvisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
