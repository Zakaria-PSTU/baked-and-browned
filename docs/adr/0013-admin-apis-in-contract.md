# ADR-0013: Admin operations via API; seed data limited to reference data

- Status: Accepted
- Date: 2026-11-16

## Context
v1.0 lacked APIs for categories, customization, zones, inventory read, recipes, slot updates, status history, payment verification and reporting. Using seed migrations as a substitute would make operational data changes require deployments and risk hard-coded credentials.

## Decision
- Extend `api-contract.yaml` (v1.1, backward-compatible) with admin endpoints for: categories; customization groups/options and product assignment; product recipes; delivery zones; delivery slot list/update; inventory list/create/adjust/movements; order detail and status history; payment list, proof download, verification; refunds; reconciliation cases; dashboard summary and daily sales report.
- All `/admin/**` operations require ADMIN (default-deny security config; 403 documented on every admin operation) and write `audit_events` for changes.
- Flyway seed data is restricted to reference/configuration data (e.g. inactive delivery zones). Migrations never create users or credentials. The first ADMIN is provisioned by an operator-run one-off command reading credentials from the environment, refusing to run if an ADMIN exists.

## Consequences
- Larger admin surface to test for authorization (every admin operation gets a CUSTOMER → 403 test).
- `revenueToday` defined as booked revenue; `collectedToday` added. Definition approved 2026-11-17.
