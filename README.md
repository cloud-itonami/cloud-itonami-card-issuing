# cloud-itonami-card-issuing

[![ci](https://github.com/cloud-itonami/cloud-itonami-card-issuing/actions/workflows/ci.yml/badge.svg)](https://github.com/cloud-itonami/cloud-itonami-card-issuing/actions/workflows/ci.yml)

Open Business Blueprint for **card issuing**: the issuer side of the
card network -- BIN/range sponsorship coordination, cardholder account
provisioning, issuer-side real-time authorization-decision policy, card
lifecycle management, and issuer-side dispute/chargeback initiation.
This repository publishes a card-issuing-program actor as an OSS
business that any qualified, licensed operator (a card-issuing bank or
EMI, or a program manager acting under one) can fork, deploy, run,
improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, in-mem checkpoints) -- the same actor pattern as every
other actor in this fleet, including
[`cloud-itonami-isic-6419`](https://github.com/cloud-itonami/cloud-itonami-isic-6419)
(banking; Ops-LLM ⊣ Monetary Intermediation Governor),
[`cloud-itonami-isic-6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492)
(credit granting),
[`cloud-itonami-isic-6619`](https://github.com/cloud-itonami/cloud-itonami-isic-6619)
(card transaction processing/settlement -- the acquirer/merchant side,
see `Scope` below), and
[`cloud-itonami-isic-6910`](https://github.com/cloud-itonami/cloud-itonami-isic-6910)
(Registrar-LLM ⊣ RegistrarGovernor, the closest architectural sibling to
this repo's R0 maturity). Here it is **Card Issuing Advisor ⊣ Card
Issuing Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a
> cardholder-intake summary, normalizing records, and checking whether
> a transaction's own amount stays within a funding account's own
> daily limit -- but it has **no notion of which jurisdiction's
> card-issuing supervisory framework is official, no license to
> actually sponsor a BIN with a card scheme or issue a real card, and
> no way to know on its own whether a sanctions flag against a
> cardholder has actually stayed unresolved**. Letting it commit a real
> BIN sponsorship, issue a real card, apply a real card-lifecycle
> transition, or approve a real authorization directly invites
> fabricated jurisdiction citations, an authorization decision that
> quietly exceeds its own account's limit, and an unresolved sanctions
> hit being overlooked -- and real financial and regulatory liability
> for whoever runs it. This project seals the Card Issuing Advisor into
> a single node and wraps it with an independent **Card Issuing
> Governor**, a human **approval workflow**, and an immutable **audit
> ledger**.

## Scope: what this actor does and does not do

This actor covers cardholder intake, BIN/scheme sponsorship-requirement
assessment, KYC/sanctions screening, BIN/range sponsorship commitment,
card issuance, card-lifecycle management (activate/block/reissue/
close), real-time issuer authorization decisions, and issuer-side
dispute/chargeback initiation -- the **issuer** side of the four-party
card network model.

It does **not**, by itself, hold any license required to sponsor a BIN
with a card scheme or issue cards in a given jurisdiction, and it does
not claim to. It also does **not** handle a raw PAN (Primary Account
Number) at any point -- `cardissuing.store`'s schema has no PAN field
at all, by design, mirroring `cloud-itonami-isic-6619`'s own posture.
The `:card-reference` this actor's own registry constructs is a
SYNTHETIC, Luhn-checkable identifier for its own draft records, never a
real network-issued PAN (see `cardissuing.registry`'s docstring).

It also explicitly does **not**:

- perform **merchant acquiring or transaction settlement on the
  acquirer side** -- that is
  [`cloud-itonami-isic-6619`](https://github.com/cloud-itonami/cloud-itonami-isic-6619)'s
  scope (transaction intake, fraud screening, settlement finalization,
  acquirer-side chargeback-hold release). This actor's `:dispute/
  initiate` is the **cardholder-facing, issuer-side** dispute/
  chargeback-initiation step -- the opposite direction on the same card
  network from `isic-6619`'s merchant-facing chargeback handling. The
  two actors are siblings, not duplicates: a real dispute flows from
  this actor's `:dispute/initiate` into the card scheme's dispute
  process, which is what eventually produces the chargeback
  `isic-6619` processes on the acquirer/merchant side.
- **issue e-money itself** -- that is `cloud-itonami-emi`'s scope. This
  actor only *links* a card to an e-money wallet as one possible
  funding source (alongside a bank deposit account from
  [`cloud-itonami-isic-6419`](https://github.com/cloud-itonami/cloud-itonami-isic-6419)
  or a credit line from
  [`cloud-itonami-isic-6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492)),
  it never mints, redeems or custodies the underlying e-money.
- implement the **broader PSD2 payment-service execution** surface --
  that is `cloud-itonami-pi`'s scope. This actor is limited to
  issuer-specific concerns (BIN sponsorship, card issuance, card
  lifecycle, authorization decisions, issuer-side disputes).
- implement **PAN/Luhn validation as a network capability, ISO 8583
  message handling, or actual physical card manufacturing/EMV chip
  personalization logistics** -- the Luhn check in `cardissuing.
  registry` validates this actor's OWN synthetic draft identifier, not
  a real network PAN. Real EMV chip personalization and physical card
  fulfillment are treated as an external vendor integration point, not
  modeled in this actor's domain logic (see `docs/adr/0001-
  architecture.md`).

Whoever deploys and operates a live instance (a licensed card-issuing
bank/EMI, or a program manager acting under one) supplies any
jurisdiction-specific license, the real card-scheme membership/BIN
sponsorship agreement, the real PCI-DSS/EMV infrastructure and the real
card-network integrations, and bears that jurisdiction's liability --
the software supplies the governed, spec-cited, audited execution
scaffold so that operator does not have to build the compliance layer
from scratch for every new market.

### Actuation

**A real BIN/range sponsorship commitment, a real card issuance, a real
card-lifecycle transition, a real issuer authorization decision against
real funds/credit, or a real dispute/chargeback initiation is never
autonomous, at any phase, by construction.** Two independent layers
enforce this (`cardissuing.governor`'s `:actuation` high-stakes gate
and `cardissuing.phase`'s phase table, which never puts `:bin/sponsor`,
`:card/issue`, `:card/lifecycle`, `:authorization/decide` or
`:dispute/initiate` in any phase's `:auto` set) -- see `cardissuing.
phase`'s docstring and `test/cardissuing/phase_test.clj`'s
`actuation-ops-never-auto-at-any-phase`. The actor may draft, check,
screen and recommend; a human issuer risk/compliance operator is always
the one who actually commits a sponsorship, issues a card, transitions
its lifecycle, authorizes a transaction, or initiates a dispute.

**`:cardholder/intake` is the one op that DOES auto-commit** (it's the
only member of any phase's `:auto` set -- pre-issuance cardholder data
entry needs to be fast, not gated). To keep that from becoming a
backdoor around everything above, the governor blocks intake outright
once a cardholder's card has moved past `:intake`
(`:post-issue-intake-blocked`): every further change to the funding
link, BIN or status must go through `:card/lifecycle` instead, which
carries a full ground-truth ledger check and a human-approval gate.

## The core contract

```
cardholder intake + BIN/scheme facts (cardissuing.facts, spec-cited)
        |
        v
   ┌────────────────┐   proposal      ┌────────────────────────┐
   │ Card Issuing    │ ─────────────▶ │ Card Issuing Governor   │  (independent system)
   │ Advisor         │  + citations   │ spec-basis · KYC ·      │
   │ (sealed)        │                │ funding-verified ·      │
   └─────────────────┘                │ BIN-sponsored · Luhn    │
                             commit ◀──┼──────────▶ hold (fabricated law;
                                 │              │      sanctions hit; unsponsored
                           record + ledger  escalate ─▶ 人間承認   BIN; velocity/MCC/funds
                              (ALWAYS for :bin/sponsor /              exceeded; un-overridable)
                               :card/issue / :card/lifecycle /
                               :authorization/decide /
                               :dispute/initiate)
```

**The Card Issuing Advisor never sponsors a BIN, issues a card,
transitions a lifecycle, decides an authorization or initiates a
dispute the Card Issuing Governor would reject, and never does so
without a human sign-off.** Hard violations (fabricated jurisdiction
requirements, an unresolved sanctions hit, an unverified funding
account, an unsponsored BIN, a checksum-invalid card reference, a
double actuation, an illegal lifecycle transition, a velocity/MCC/
insufficient-funds breach) force **hold** and *cannot* be approved
past; a clean proposal still always routes to a human.

## The HTTP surface: two listeners, four answers

`cardissuing.http` is what a consent surface (`cloud-itonami-app`) hands proposals
to. Same two-listener shape as `cloud-itonami-esim`, for the same reason:

| | | |
|---|---|---|
| consent | `:1341` | `POST /commit`, `GET /proposals/<ref>` — `X-CARD-CONSENT-TOKEN`; `GET /healthz` open |
| operator | `:1342` | `POST /proposals/<ref>/decide` — `X-CARD-OPERATOR-TOKEN` |

**両面ともトークンを要求し、しかも別のトークン**です。app の consent token を持っていても
自分の提案を自分で承認することはできません — 2つの gate が2つであることの全部がそこで、
共有シークレットにすればそれを静かに1つにしてしまいます。`CARD_CONSENT_TOKEN` が未設定なら
**503 で全ての proposal を拒否**します（loopback 束縛は認可ではありません — ホスト上の
すべてのプロセスがそれを共有しています）。

If `decide` sat on the consent surface, the consent surface could approve its own
proposals — it would hold both gates and the containment would be a comment.
Different listeners means it cannot reach `decide` because it is not listening
there. The operator surface requires `CARD_OPERATOR_TOKEN` and refuses **every**
decide when that is unset, because failing open would make it most dangerous
exactly when nobody had configured it.

**What is different here from the eSIM actor: this one can actually issue a card.**
So approving and issuing are different events and are reported separately:

```
{"status":"committed",              "record":{…,"provider":{…}}}   approved AND actuated
{"status":"approved-not-actuated",  "approval-recorded":true, …}   approval RECORDED, provider refused or absent
{"status":"held",                   "refusal":{…}}                 governor refused — never reaches an operator
{"status":"pending",                "reference":"…"}               awaiting this actor's operator
```

`approved-not-actuated` is the state that matters and the one a two-state answer
would destroy. The graph commits **first**, so the approval is in the ledger
regardless of what Stripe then does; the provider is called **second**. Collapsing
them would either lose an approval that really happened or claim a card exists when
the provider said no. The idempotency key is **derived from the reference**, so a
retried `decide` cannot issue a second card — that is the key
`kotoba.card.actuation` requires a caller to supply and deliberately refuses to
generate itself.

The actuator is **injected and defaults to absent**: with none configured, every
approval answers `approved-not-actuated` with `:no-actuator-configured`. A live one
is opt-in via `CARD_ACTUATOR=1` (+ `CARD_ACTUATOR_MODE=live`, which must be set
deliberately — the provider defaults to Stripe **test** mode).

### The cardholder gap, stated rather than papered over

Two facts about the actor's own phase table collide here, and the surface reports
the collision instead of hiding it:

- **`:cardholder/intake` auto-commits at phase 3** (it is the one member of that
  phase's `:auto` set), so no operator ever approves it — and
  `kotoba.card.actuation/precheck` refuses a call whose approval names nobody. So
  an auto-commit answers `"committed"` **plus** `"actuation":"not-attempted"` with
  `"auto-committed-without-operator-approval"`. It writes the local draft; it does
  not create a Stripe cardholder.
- **`:card/issue` needs Stripe's cardholder id** (`ich_…`), which is *not* this
  actor's own `ch-1`. Substituting one for the other would create a card against a
  cardholder that does not exist — or against someone else's. So when
  `:stripe-cardholder-id` is absent the actuation is refused with
  `:stripe-cardholder-unknown` **before any outbound call**, rather than guessed.

The consequence, said plainly: **at phase 3 there is no approved path that creates
the Stripe cardholder.** Either the id is supplied by whoever created it, or the
deployment runs at phase 2 — where intake becomes operator-gated but `:card/issue`
leaves `:writes` entirely. That is a real remaining gap in the integration, not a
property of this surface.

## Run

```bash
clojure -M:serve       # both listeners (consent :1341, operator :1342)
clojure -M:west:run    # walk one clean sponsor -> issue -> activate -> authorize -> dispute lifecycle, plus three HARD-hold cases, through the actor
clojure -M:test        # governor contract · phase invariants · store contract · registry (Luhn) conformance · facts coverage · real-LLM advisor
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Card Issuing Governor, sponsorship/issuance/lifecycle/authorization/dispute draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Layout

| File | Role |
|---|---|
| `src/cardissuing/store.cljc` | **Store** protocol -- `MemStore` only at this R0 maturity (a future Datomic/kotoba-server backend is a planned seam, not yet implemented) + append-only audit ledger + sponsorship/issuance/lifecycle/authorization/dispute history. No raw PAN field anywhere in the schema |
| `src/cardissuing/registry.cljc` | Luhn (ISO/IEC 7812-1 mod-10) synthetic card-reference construction + BIN/range sponsorship, card-issuance, card-lifecycle-event, authorization-decision and dispute-initiation draft records |
| `src/cardissuing/facts.cljc` | Per-jurisdiction card-issuing supervisory-requirement catalog with an official spec-basis citation per entry, honest coverage reporting, restricted-MCC catalog |
| `src/cardissuing/cardissuingadvisor.cljc` | **Card Issuing Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/KYC/sponsorship/issuance/lifecycle/authorization/dispute proposals |
| `src/cardissuing/governor.cljc` | **Card Issuing Governor** -- 16 HARD checks (effect-match · spec-basis · sanctions · KYC-complete · funding-verified · BIN-sponsored · card-reference-Luhn · already-issued/-sponsored · lifecycle-transition-valid · post-issue-intake-block · intake-fabrication · velocity/MCC/insufficient-funds · already-decided/-disputed/dispute-target) + 2 soft (confidence/actuation gate) |
| `src/cardissuing/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess/screen → supervised (BIN sponsorship, card issuance, lifecycle, authorization and dispute initiation always human) |
| `src/cardissuing/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/cardissuing/sim.cljc` | demo driver |
| `test/cardissuing/*_test.clj` | governor contract · phase invariants · store contract · registry (Luhn) conformance · facts coverage · real-LLM advisor |

## Jurisdiction coverage (honest)

`cardissuing.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `cardissuing.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `cardissuing.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to
make coverage look bigger.

## Maturity

R0 -- Card Issuing Advisor + Card Issuing Governor run as real, tested
code (see `Run` above; **93 tests / 329 assertions**, lint clean —
including the HTTP surface driven over real sockets with a recording
actuator, so "the provider was never called" is asserted rather than
assumed). Every write path of the Stripe provider itself is still
**unexercised against Stripe test mode** — see `io-stripe-issuing`'s own
README, which says so before anything else. Store is
`MemStore` only; a Datomic/kotoba-server backend and the WASM-kernel
tier `cloud-itonami-isic-6619` already has (`wasm/*.kotoba`, hosted
under `kototama.tender`) are both future maturity-promotion paths, not
yet implemented -- see `docs/adr/0001-architecture.md`.

## License

Code and implementation templates are AGPL-3.0-or-later.
