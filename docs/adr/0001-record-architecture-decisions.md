# ADR-0001: Record architecture decisions (MADR)

- Status: Accepted
- Implementation review: 2026-10-09 (v1.0.0; limitations are documented in architecture §17)
- Date: 2026-10-08
- Related: architecture §18

## Context

The platform is a reference implementation whose value lies as much in *why* it is built a certain way as in the
code itself. Reviewers (technical clients, hiring managers) need to see the trade-offs behind each significant
choice, and contributors need a stable record so that decisions are not silently re-litigated or
contradicted by later changes.

## Decision

Record every architecturally significant decision as an Architecture Decision Record in `docs/adr/`, using a
lightweight MADR layout:

- File name `NNNN-kebab-case-title.md`, numbers are never reused.
- Header with Status (`Proposed`, `Accepted`, `Superseded by ADR-XXXX`, `Deprecated`), Date and related sections.
- Sections: Context, Decision, Alternatives considered, Consequences.

`docs/architecture.md` remains the single source of truth for the design; ADRs capture the reasoning. If a task
requires a different decision, a new ADR is proposed and the old one is marked superseded — accepted ADRs are not
edited to change their meaning.

## Alternatives considered

- **Decisions only inside architecture.md** — keeps one file, but loses history and rationale as the document evolves.
- **Wiki / issue tracker** — separated from the code, not versioned with it, invisible in code review.
- **Nygard's original template** — fine, but MADR's explicit "Alternatives considered" section is exactly what
  reviewers of this project look for.

## Consequences

- Decisions are reviewable in pull requests together with the code that implements them.
- Small overhead per significant change; trivial choices do not get an ADR.
- Code and docs disagreeing is treated as a defect: docs win until they are changed explicitly in the same commit.
