# ADR-0015: Frozen V1 baseline, additive migrations

- Status: Accepted
- Date: 2026-11-16

## Context
`database.md` v1.0 embedded an executable baseline. v1.1 decisions require schema changes. Keeping a clear trail from the original specification matters more than a single tidy file.

## Decision
- `V1__init.sql` is the v1.0 DDL verbatim. Changes are additive migrations: V2 foundation hardening, V3 delivery zones, V4 ordering policy, V5 payments.
- An applied migration is never edited; fixes go in a new migration.
- `ddl-auto=validate` in all environments; Testcontainers test applies all migrations to PostgreSQL 17 and boots the JPA context in CI.
- V2–V5 add some `NOT NULL` columns to `orders` without defaults; this is valid only because no environment holds order data yet. After the first deployment with data, such changes need backfill steps.

## Consequences
- `database.md` documents each migration's SQL; the files under `backend/src/main/resources/db/migration/` are exact copies, checked in CI against the doc.
- Native PostgreSQL enum mapping under Hibernate 7 with `validate` is verified in Phase 1 before entity work begins.
