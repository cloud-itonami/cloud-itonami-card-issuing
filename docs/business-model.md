# Open Business Blueprint: cloud-itonami-card-issuing

This repository publishes an OSS business model for operating a
card-issuing program (BIN/range sponsorship, cardholder provisioning,
card lifecycle, issuer-side authorization and dispute handling) on
itonami.cloud.

## Classification

- Repository name: `cloud-itonami-card-issuing`
- Primary domain: `:finance/card-issuing`
- Activity: issuer-side card program management (BIN/range sponsorship
  coordination, cardholder account provisioning, card lifecycle,
  real-time authorization-decision policy, issuer-side dispute/
  chargeback initiation)
- Served domain: general-purpose card issuing, any legitimate use case
  -- not limited to any single industry
- Original implementation context: commissioned as the issuer-side
  counterpart to `cloud-itonami-isic-6619` (acquirer/card-processing
  side), identified as a gap during a payment-industry banking/
  jurisdiction research project (`90-docs/adr/2607246000` in the
  `com-junkawasaki/root` superproject)

## Customer

Primary customers:

- fintechs and program managers who want to launch a card program
  without building an issuer-processor stack from scratch
- accelerators / neobanks that need a governed, auditable cardholder
  provisioning + authorization-policy tool instead of ad hoc scripts
- licensed card-issuing banks/EMIs who want a governed execution
  scaffold for BIN sponsorship and card-lifecycle management
- operators already running `cloud-itonami-isic-6419` (banking) or
  `cloud-itonami-isic-6492` (credit) who want to offer a card product
  against the same funding accounts

## Problem

Card-issuing programs today are either built entirely in-house (slow,
expensive, hard to audit) or fully outsourced to a black-box processor
(no visibility into WHY an authorization was declined, no auditable
trail of who approved a BIN sponsorship or a card-lifecycle change). A
program manager has no way to verify why a transaction was declined, or
to prove after the fact that a card issuance was screened and approved
properly.

## Offer

Operators provide a governed card-issuing program tool:

- cardholder intake and normalization
- per-BIN/scheme sponsorship-requirement checklist, always citing an
  official source (never a fabricated requirement)
- KYC / sanctions screening gate on every cardholder
- BIN/range sponsorship commitment drafting, human-approved
- card issuance with a synthetic, Luhn-checkable draft card reference
  (never a real PAN)
- card-lifecycle management (activate/block/reissue/close), each
  transition governed and human-approved
- real-time issuer authorization-decision policy (velocity limits,
  MCC restrictions, balance/credit-limit checks), independently
  re-verified by the governor
- issuer-side dispute/chargeback initiation, human-approved
- immutable audit ledger of every draft, hold, and approval

The core promise: the Card Issuing Advisor can draft and check, but it
cannot sponsor a BIN, issue a card, transition its lifecycle, authorize
a transaction, or initiate a dispute unless a human operator -- who
holds the actual license and liability -- approves.

## Revenue

Operators can sell:

- per-card issuance fee
- per-authorization processing fee
- jurisdiction-pack licensing: a maintained, spec-cited requirement
  catalog for a specific country, kept current
- managed hosting: monthly subscription per program (fintech,
  accelerator, neobank)
- KYC/sanctions-screening add-on (integration with a real screening
  provider is the operator's responsibility)
- compliance package: audit export, retention, security review

| Package | Customer | Price shape |
|---|---|---|
| Per-card | individual program manager | flat fee per card issued |
| Per-authorization | high-volume program | per-transaction fee |
| Jurisdiction pack | card-issuing bank/EMI | subscription per country covered |
| Managed tenant | fintech / accelerator | monthly platform fee |
| Operator enablement | new card-issuing bank/EMI | training + certification |

## Unit Economics

Track these numbers for every operator:

- setup hours per new jurisdiction added to `cardissuing.facts`
- LLM cost per intake/assessment/screening/authorization operation
- KYC/sanctions-screening provider cost per cardholder
- human-approval hours per BIN sponsorship / card issuance / lifecycle
  transition / authorization / dispute
- incident and audit hours
- gross margin after infrastructure, screening-provider, card-scheme
  and support costs

## Open Participation

Anyone may:

- fork the repository
- run the demo
- deploy a self-hosted instance
- submit issues and patches
- publish an additional jurisdiction pack (with a real official
  spec-basis citation)
- create a local operator business

itonami.cloud should require certification -- including proof of the
jurisdiction's actual card-issuing/BIN-sponsorship license -- before
listing an operator as a trusted provider or routing customer leads.

## Operator Trust Levels

| Level | Capability |
|---|---|
| Contributor | patches, docs, issues, examples, jurisdiction packs |
| Self-host operator | runs their own instance with no platform endorsement |
| Certified operator | listed on itonami.cloud after review, including jurisdiction licensing proof |
| Managed operator | may receive leads and operate customer tenants |
| Core maintainer | can approve changes to governor, security and governance |

## Marketplace Metadata

```edn
{:itonami.blueprint/id "cloud-itonami-card-issuing"
 :itonami.blueprint/name "Card Issuing (Issuer-Side Card Program Management)"
 :itonami.blueprint/domain :finance/card-issuing
 :itonami.blueprint/license "AGPL-3.0-or-later"
 :itonami.blueprint/operator-model :certified-open-business
 :itonami.blueprint/repo "https://github.com/cloud-itonami/cloud-itonami-card-issuing"
 :itonami.blueprint/status :public-oss}
```

## Non-Negotiables

- Do not commit real cardholder/funding-account identification
  documents or screening results.
- Do not add a raw PAN field to this actor's schema, ever.
- Do not bypass the Card Issuing Governor for a BIN sponsorship, card
  issuance, lifecycle transition, authorization decision or dispute
  initiation.
- Do not add a jurisdiction to `cardissuing.facts` without a real,
  citable official source.
- Do not market an uncertified deployment as an itonami.cloud certified
  operator, and do not operate in a jurisdiction without the license
  that jurisdiction actually requires of a card issuer.
