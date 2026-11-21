# Architecture Decision Records

Format: Context / Decision / Consequences. An accepted ADR is never edited to change its decision; it is superseded by a new ADR.

| ADR | Title | Status | Date |
|---|---|---|---|
| 001 | Modular monolith | Accepted (architecture.md v1.0) | — |
| 002 | PostgreSQL as source of truth | Accepted (architecture.md v1.0) | — |
| 003 | Transactional outbox for side effects | Accepted (architecture.md v1.0) | — |
| 004 | Server-owned pricing | Accepted (architecture.md v1.0) | — |
| 005 | Hosted payment page | Accepted (architecture.md v1.0) | — |
| 006 | No external calls inside transactions | Accepted (architecture.md v1.0) | — |
| [0007](0007-java-25.md) | Java 25 runtime | Accepted | 2026-11-16 |
| [0008](0008-payment-hold-and-expiry.md) | 15-minute payment hold and idempotent expiry | Accepted | 2026-11-16 |
| [0009](0009-cancellation-cutoff.md) | Per-product cancellation cutoff, strictest wins | Accepted | 2026-11-16 |
| [0010](0010-delivery-zones.md) | Database-driven Dhaka delivery zones | Accepted | 2026-11-16 |
| [0011](0011-manual-payment-verification.md) | COD and admin-verified manual payments | Accepted | 2026-11-16 |
| [0012](0012-payment-gateway-port.md) | Provider-agnostic PaymentGateway; SSLCommerz candidate | Accepted | 2026-11-16 |
| [0013](0013-admin-apis-in-contract.md) | Admin operations via API; seed data limited to reference data | Accepted | 2026-11-16 |
| [0014](0014-order-detail-v1-1.md) | v1.1 order detail and customer/admin DTO split | Accepted | 2026-11-16 |
| [0015](0015-migration-policy.md) | Frozen V1 baseline, additive migrations | Accepted | 2026-11-16 |
| [0016](0016-sms-notifications.md) | SMS order notices through a provider-agnostic SmsSender | Accepted | 2026-11-18 |
| [0017](0017-refund-review-and-partial-refunds.md) | Second-approver refund review and partial refunds | Accepted | 2026-11-18 |
| [0018](0018-sslcommerz-adapter.md) | SSLCommerz adapter from the public v4 docs | Proposed | 2026-11-18 |
| [0019](0019-option-costs.md) | Customization options carry kitchen costs | Accepted | 2026-11-18 |
| [0020](0020-disallowed-combinations.md) | Disallowed option combinations per product | Accepted | 2026-11-18 |
| [0021](0021-customization-display-order.md) | Per-product display order of customization groups and options | Accepted | 2026-11-18 |
| [0022](0022-manual-payment-accounts.md) | Manual payment receiving accounts from configuration | Accepted | 2026-11-18 |
| [0023](0023-refresh-recovery-after-aborted-navigation.md) | Strict refresh rotation with browser-side recovery from aborted navigations (revision 4) | Proposed | 2026-11-20 |
| [0024](0024-additional-admin-provisioning.md) | Provision additional admins through the operator command (revision 3) | Proposed | 2026-11-19 |
| [0025](0025-absolute-session-lifetime-and-revocation-synchronization.md) | Absolute session lifetime and revocation synchronization | Proposed | 2026-11-20 |
| [0026](0026-key-bound-refresh-tokens.md) | Key-bound refresh tokens (proof of possession), future | Proposed | 2026-11-20 |
| [0027](0027-live-sms-provider.md) | Live SMS provider: SSL Wireless ISMS Plus, failed and expired notices | Proposed | 2026-11-20 |
| [0028](0028-account-state-on-every-request.md) | Check the account's state on every authenticated request | Proposed | 2026-11-20 |
| [0029](0029-account-disable-command.md) | Disable and re-enable accounts through the operator command | Proposed | 2026-11-20 |
