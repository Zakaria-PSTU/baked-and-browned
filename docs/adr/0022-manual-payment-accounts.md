# ADR-0022: Manual payment receiving accounts from configuration

- Status: Accepted
- Date: 2026-11-18
- Builds on: ADR-0008, ADR-0011

## Context
ADR-0011 left open which bank accounts and mobile wallet numbers customers are told to pay to. A customer who picks MANUAL at checkout has 15 minutes (ADR-0008) to send the money and submit the transaction reference, so the web app has to show where to send it. The details are the business's own, differ between staging and production, and may change (a new merchant number).

## Decision
- **Configuration, not code or database.** `app.payments.manual.accounts` is a list of `{provider, label, account-name, account-number, note?}`, set per deployment. These are published receiving details, not secrets. Invalid entries (blank label, name or number, over-long values) fail at startup.
- **Empty by default.** With no accounts configured, the web app does not offer MANUAL at checkout.
- **API:** `GET /api/v1/payments/manual-accounts` (signed in, like checkout) returns the list in the configured order. Contract v1.3.
- **No admin UI.** Changing an account is a deployment, which is rare and leaves a trace in the deployment history.

## Consequences
- The real accounts must be configured before manual payment goes live. That is the business configuration ADR-0011 already called for.
- The server still accepts a MANUAL order when no account is configured (for example from an older client). The web app hides the option; rejecting such orders server-side is a possible follow-up.
- Considered and rejected: a database table with an admin API. It is more flexible, but it adds audit, validation and screens for something that changes about once a year.
