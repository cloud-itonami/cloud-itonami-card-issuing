# Operator Guide

This guide is for people who want to start an open business from
`cloud-itonami-card-issuing`.

## 1. Fork and Run

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-card-issuing
cd cloud-itonami-card-issuing
clojure -M:dev:test
clojure -M:dev:run
```

The default demo uses synthetic cardholders and funding accounts.
Production cardholder data, identification documents and screening
results must stay outside the repository and be injected through a
store adapter.

## 2. Choose an Operating Mode

| Mode | Use when |
|---|---|
| Demo | validating the actor and governor contract |
| Self-host | one operator owns infrastructure and cardholder data |
| Managed tenant | an operator hosts for a fintech / accelerator |
| Certified operator | itonami.cloud has reviewed license, security and process controls |

## 3. Production Checklist

- confirm you (the operator) hold whatever license the target
  jurisdiction requires of a card-issuing bank/EMI, or that you operate
  under a bank/EMI that does -- this software does not grant or
  substitute for one
- replace demo data with a customer-owned store
- configure Datomic Local, kotoba-server or an equivalent durable SSoT
  (this actor is `MemStore`-only at R0 -- see README `Maturity`)
- configure the LLM adapter through environment variables or a secret
  manager
- integrate a real KYC/sanctions-screening provider behind
  `cardissuing.cardissuingadvisor`'s `:kyc/screen` path
- integrate a real card scheme (Visa/Mastercard/etc.) BIN sponsorship
  agreement and PAN tokenization/EMV personalization pipeline --
  neither is modeled in this actor's domain logic (see README `Scope`)
- extend `cardissuing.facts/catalog` for every jurisdiction you serve,
  each entry citing the jurisdiction's own official supervisory
  authority as `:provenance`
- run `clojure -M:dev:test`
- run `clojure -M:lint`
- verify audit-ledger export
- document backup and restore
- document incident response
- get written approval for handling cardholder identification
  documents

## 4. Sales Motion

Start with a narrow offer:

1. one jurisdiction, one funding-source type (e.g. bank deposit via
   `cloud-itonami-isic-6419`)
2. prove the governed cardholder-intake + KYC-screening + card-issuance
   flow end-to-end
3. run one BIN sponsorship and one card issuance through human approval
4. export the audit ledger for the customer's own records
5. expand to a second jurisdiction or funding-source type only after
   the first is repeatable

Avoid selling "issue cards anywhere, against anything" before the
jurisdiction pack and the human-approval workflow for that jurisdiction
actually exist and have been exercised.

## 5. Certification Requirements

itonami.cloud certification should require:

- passing tests and lint on the published version
- proof of the operator's card-issuing/BIN-sponsorship license where
  the jurisdiction requires one
- written data-flow diagram, including where KYC documents and PAN
  tokens (outside this repository) are stored
- backup/restore evidence
- incident contact and response window
- proof that every BIN sponsorship, card issuance, lifecycle
  transition, authorization decision and dispute initiation passes
  through a human approval step (never bypassed, never auto-committed
  -- see README `Actuation`)
- proof that real cardholder identification documents are not stored
  in Git
- customer-facing support terms

## 6. Operator Responsibilities

Operators are responsible for:

- holding the actual license/registration a jurisdiction requires of a
  card-issuing bank/EMI, or operating under one that does
- customer consent and lawful basis for KYC data processing
- the real integration with each card scheme's membership/BIN
  sponsorship process and PAN tokenization/EMV personalization pipeline
- secure infrastructure and tenant isolation
- human approval workflow staffing (someone has to actually review and
  approve each sponsorship, issuance, lifecycle transition,
  authorization and dispute)
- data-retention policy for identification documents
- security updates

The OSS project provides software and an operating blueprint. It does
not make an operator licensed, KYC-compliant, or legally authorized to
sponsor a BIN or issue cards on anyone's behalf by itself.
