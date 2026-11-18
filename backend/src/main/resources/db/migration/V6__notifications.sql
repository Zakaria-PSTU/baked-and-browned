-- Customer notices delivered from outbox events: one row per event and channel, so a redelivered
-- event never sends twice. The body is fixed at the first attempt and resent unchanged on retries.
CREATE TYPE notification_channel AS ENUM ('SMS','EMAIL');
CREATE TYPE notification_status AS ENUM ('PENDING','SENT');
CREATE TABLE notifications (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 outbox_event_id UUID NOT NULL,
 order_id UUID NOT NULL REFERENCES orders(id),
 user_id UUID NOT NULL REFERENCES users(id),
 kind VARCHAR(40) NOT NULL
   CHECK (kind IN ('ORDER_RECEIVED','ORDER_CONFIRMED','ORDER_DISPATCHED','ORDER_CANCELLED','ORDER_EXPIRED')),
 channel notification_channel NOT NULL,
 recipient VARCHAR(255) NOT NULL,
 body VARCHAR(1000) NOT NULL,
 status notification_status NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
 provider VARCHAR(60),
 provider_message_id VARCHAR(150),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 sent_at TIMESTAMPTZ,
 CONSTRAINT uq_notification_event_channel UNIQUE (outbox_event_id, channel),
 CHECK (status <> 'SENT' OR (sent_at IS NOT NULL AND provider IS NOT NULL))
);
CREATE INDEX idx_notifications_order ON notifications(order_id, created_at);
