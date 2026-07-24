# Contributing

`cloud-itonami-card-issuing` accepts contributions to the OSS actor,
governor tests, documentation, jurisdiction packs and open business
blueprint.

## Development

```bash
clojure -M:dev:test
clojure -M:lint
```

Keep changes small and include tests for governor, phase, registry or
facts-coverage behavior.

## Rules

- Do not commit real cardholder applications, credentials,
  identification documents or screening results.
- Never add a raw PAN (Primary Account Number) field to `cardissuing.
  store`'s schema -- PAN tokenization is out of scope for this actor by
  design (see README `Scope`).
- Keep BIN sponsorship, card issuance, card-lifecycle transitions,
  authorization decisions and dispute initiation behind the Card
  Issuing Governor AND the phase table -- never remove one of
  `:bin/sponsor`/`:card/issue`/`:card/lifecycle`/`:authorization/
  decide`/`:dispute/initiate` from a governor hard-check or add it to a
  phase's `:auto` set.
- Treat this as a high-risk domain: add tests for spec-basis,
  sanctions, funding-account verification, BIN sponsorship, velocity/
  MCC/insufficient-funds enforcement and audit logging with every
  change.
- A new jurisdiction entry in `cardissuing.facts/catalog` MUST cite a
  real official source (`:provenance`) -- do not add a placeholder.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which governor or phase invariant is affected
- how it was tested
- whether operator or certification docs need updates
