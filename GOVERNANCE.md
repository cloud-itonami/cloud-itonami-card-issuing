# Governance

`cloud-itonami-card-issuing` is an OSS open-business blueprint.
Governance covers both code and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- Card Issuing Advisor cannot directly sponsor a BIN, issue a card, or
  authorize a real transaction.
- Card Issuing Governor remains independent of the advisor.
- hard governor violations (fabricated spec-basis, sanctions hit,
  unverified funding account, unsponsored BIN, checksum-invalid card
  reference, double actuation, illegal lifecycle transition, velocity/
  MCC/insufficient-funds breach) cannot be overridden by human approval.
- `:bin/sponsor`, `:card/issue`, `:card/lifecycle`, `:authorization/
  decide` and `:dispute/initiate` are never members of any phase's
  `:auto` set.
- every commit, hold and approval path is auditable.
- no raw PAN (Primary Account Number) is ever added to this actor's
  schema or persisted anywhere in this repository.
- real cardholder identification documents and screening results stay
  outside Git.
- no jurisdiction is added to `cardissuing.facts` without a real,
  citable official source.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, actuation invariant, public business model, operator
certification or license should add or update an ADR.
