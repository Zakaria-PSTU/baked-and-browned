# ADR-0016: SMS order notices through a provider-agnostic SmsSender

- Status: Accepted
- Date: 2026-11-18

## Context
Business rules require order received, confirmed, dispatched and cancelled notices, sent from the outbox after commit, retryable and idempotent, and never rolling back an order. Every account has a Bangladeshi mobile number; email is optional. No SMS provider account exists yet.

## Decision
- SMS is the first channel, sent to the account holder's phone (`users.phone`), not the order's delivery contact, who may be a gift recipient.
- The notification module defines an `SmsSender` port. Only a `FakeSmsSender` exists, for dev/test profiles; it refuses to start under `prod`. A live adapter is added once a provider is chosen; until then a `prod` context has no `SmsSender` and fails at startup instead of silently dropping notices.
- One outbox handler per order event (`order.placed`, `order.confirmed`, `order.cancelled`, `order.expired`). The `order.dispatched` notice follows when admin order operations publish that event.
- `notifications` (V6) records one row per outbox event and channel. The first attempt fixes recipient and text; retries resend the same text with the row id as idempotency key; a SENT notice is never sent again. A crash between a provider's acceptance and recording it can resend once (at-least-once).
- Texts are plain ASCII, at most 160 characters (one GSM-7 segment), with times in Asia/Dhaka, and contain only system values: never names, addresses, notes or gift messages typed by customers.

## Business configuration still required before launch
- SMS provider, sender ID / masking registration and credentials (secret store).
- Whether customers may opt out of non-essential notices.

## Consequences
- Adapters must not log message text or full phone numbers (`SmsMessage.toString` masks them).
- An email channel can be added under the same table (`notification_channel` already has `EMAIL`).
