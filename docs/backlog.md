# Backlog

Ideas and follow-ups that are out of scope for the current task. Each entry: what, why, and the related section/ADR.

## Dead-letter persister: no safe default

- **What:** `platform.dead-letters.persister.topic-pattern` defaults to `.*-dlt`, so a service that does not set it stores
  the dead letters of every service on the broker. T17 found it (both services kept each other's dead letters) and fixed it
  by setting the pattern in each service's `application.yml`. The starter could instead derive the pattern from the topics
  its own listeners consume, so that a third service cannot make the same mistake.
- **Why not now:** needs a way to know the consumed topics at auto-configuration time; out of scope for T17.
- **Related:** architecture §7.4, ADR-0007, runbook `dlq.md`.
