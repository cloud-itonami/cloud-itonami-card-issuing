# Security Policy

This project handles card-issuing workflows, including cardholder
identification, KYC/sanctions-screening results, funding-account links,
and real-time authorization decisions. Treat vulnerabilities as
potentially high impact even when the demo data is synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real cardholder, funding-account or identification-document exposure
- any path that could expose or reconstruct a real PAN (Primary Account
  Number) -- none should exist in this repository's schema at all
- authorization bypass
- Card Issuing Governor bypass
- a path that lets `:bin/sponsor`, `:card/issue`, `:card/lifecycle`,
  `:authorization/decide` or `:dispute/initiate` auto-commit at any
  phase
- audit-ledger tampering
- tenant isolation failures

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on cardholder data, governor enforcement, actuation invariant
  or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real cardholder/funding-account data outside this repository.
- Never introduce a raw PAN field into `cardissuing.store`'s schema --
  PAN tokenization is the operator's own PCI-DSS-scoped infrastructure,
  entirely out of this repository.
- Run governor and phase tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
- Never wire `:bin/sponsor`, `:card/issue`, `:card/lifecycle`,
  `:authorization/decide` or `:dispute/initiate` to run without a human
  approval step, regardless of confidence or phase.
