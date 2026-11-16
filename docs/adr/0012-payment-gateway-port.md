# ADR-0012: Provider-agnostic PaymentGateway; SSLCommerz as initial candidate

- Status: Accepted
- Date: 2026-11-16

## Context
No gateway merchant account exists yet. Gateways differ in callback encoding and verification (some deliver form-encoded IPNs that must be confirmed via a server-side validation call). Business rules forbid trusting browser redirects and calling external services inside transactions.

## Decision
- Phase 1 defines a `PaymentGateway` port in the payment module with provider-neutral types: create hosted session, verify a provider callback, query status. Results are `VerifiedPaymentResult` values (transaction id, order reference, amount, currency, final status) produced only after server-to-server verification.
- Phase 1 ships only a `FakePaymentGateway` for tests/dev profiles (never active in production profile). No live integration.
- Phase 3 implements an SSLCommerz adapter, subject to merchant onboarding and confirmation of its current API requirements at that time.
- The webhook endpoint accepts JSON or form-encoded bodies; adapters own parsing and signature/validation logic.
- Gateway calls happen outside DB transactions; state changes happen in a separate short transaction that locks the order row.

## Consequences
- Roadmap: online payments are Phase 3 (see architecture.md "Delivery roadmap").
- Contract tests for the port run against the fake; the SSLCommerz adapter adds sandbox-based tests in Phase 3.
- Gateway secrets come from the secret store; none are needed before Phase 3.
