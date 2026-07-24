(ns cardissuing.facts
  "Per-jurisdiction card-issuing regulatory catalog -- the G2-style
  spec-basis table the Card Issuing Governor checks every `:bin/assess`
  (and, transitively, every `:bin/sponsor`) proposal against ('did the
  advisor cite an OFFICIAL public source for this jurisdiction's card-
  issuing supervision/licensing framework, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every prior sibling actor's `facts` namespace uses
  (`banking.facts`/`formation.facts`/`card.facts` etc.): a jurisdiction
  not in this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official card-issuer/
  e-money-issuer prudential-supervision authority and consumer-credit-
  card regulation (see `:provenance`); they are a STARTING catalog, not
  a from-scratch survey of all ~194 jurisdictions. Extending coverage is
  additive: add one map to `catalog`, cite a real source, done -- never
  invent a jurisdiction's requirements to make coverage look bigger.

  NOTE: this catalog is about the ISSUER's own supervisory/licensing
  regime for sponsoring a BIN and issuing cards -- NOT the card scheme's
  (Visa/Mastercard) private operating rules, which are not a public
  regulatory source and are deliberately left out of this actor's
  spec-basis discipline. An operator's real Visa/Mastercard membership
  agreement is a separate, contractual concern outside this repo's
  scope (see README `Scope`).")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  identity-verification-record/funding-source-verification-record/
  account-opening-record/sanctions-screening-record evidence set every
  prior sibling's evidence checklist submits in some form;
  `:legal-basis` / `:owner-authority` / `:provenance` are the G2
  citation the governor requires before any `:bin/sponsor`/`:card/issue`
  proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "経済産業省 (Ministry of Economy, Trade and Industry, METI) / 金融庁 (Financial Services Agency, FSA)"
          :legal-basis "割賦販売法 (Installment Sales Act) -- 包括信用購入あっせん業者登録制度 / 資金決済に関する法律 (Payment Services Act)"
          :national-spec "クレジットカード発行者(包括信用購入あっせん業)登録要件 + 資金移動業/前払式支払手段発行者の登録・供託要件"
          :provenance "https://www.meti.go.jp/policy/economy/consumer_transaction/credit/index.html"
          :required-evidence ["本人確認記録 (identity-verification-record)"
                              "資金源確認記録 (funding-source-verification-record)"
                              "口座開設記録 (account-opening-record)"
                              "制裁リストスクリーニング記録 (sanctions-screening-record)"]}
   "USA" {:name "United States"
          :owner-authority "Consumer Financial Protection Bureau (CFPB) / Office of the Comptroller of the Currency (OCC)"
          :legal-basis "Truth in Lending Act (TILA) / Regulation Z, 12 C.F.R. Part 1026 (credit-card issuer disclosure, billing and authorization requirements)"
          :national-spec "Card-issuing bank/EMI Regulation Z compliance + national-bank card-program licensing (OCC) or state money-transmitter licensing for non-bank issuers"
          :provenance "https://www.consumerfinance.gov/rules-policy/regulations/1026/"
          :required-evidence ["Identity-verification record"
                              "Funding-source-verification record"
                              "Account-opening record"
                              "Sanctions-screening record"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Financial Conduct Authority (FCA)"
          :legal-basis "Electronic Money Regulations 2011 / Payment Services Regulations 2017 / Consumer Credit Act 1974 (for credit-card issuers)"
          :national-spec "E-money-institution or credit-institution card-issuing authorization + consumer-credit card-issuer conduct requirements"
          :provenance "https://www.fca.org.uk/firms/electronic-money-e-money"
          :required-evidence ["Identity-verification record"
                              "Funding-source-verification record"
                              "Account-opening record"
                              "Sanctions-screening record"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin)"
          :legal-basis "Zahlungsdiensteaufsichtsgesetz (ZAG) -- E-Geld-Institute / Kreditinstitute licensing for card issuance"
          :national-spec "E-Geld-Institut oder Kreditinstitut Kartenausgabe-Zulassung und Sanktionslistenprüfung"
          :provenance "https://www.bafin.de/DE/Aufsicht/ZahlungsdiensteEGeld/zahlungsdiensteegeld_node.html"
          :required-evidence ["Identitätsprüfungsprotokoll (identity-verification-record)"
                              "Mittelherkunftsnachweis (funding-source-verification-record)"
                              "Kontoeröffnungsprotokoll (account-opening-record)"
                              "Sanktionslisten-Screening-Protokoll (sanctions-screening-record)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to sponsor a BIN or
  issue a card on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-card-issuing R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `cardissuing.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))

;; ----------------------------- MCC restriction catalog -----------------------------

(def default-restricted-mccs
  "Merchant-category codes that a fresh cardholder authorization policy
  restricts by default (a starting, honestly-partial policy set, not a
  claim of comprehensive card-network MCC coverage). 7995 = betting/
  wagering, 5993 = cigar stores, 5122 = drugs/druggists' sundries wholesale
  -- illustrative, real ISO 18245 MCC values, not fabricated ones."
  #{"7995" "5993" "5122"})
