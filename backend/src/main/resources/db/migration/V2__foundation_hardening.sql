CREATE TYPE actor_type AS ENUM ('CUSTOMER','ADMIN','SYSTEM','GATEWAY');

-- Refresh token rotation with family-wide revocation on reuse.
ALTER TABLE refresh_tokens
  ADD COLUMN family_id UUID,
  ADD COLUMN replaced_by_id UUID REFERENCES refresh_tokens(id),
  ADD COLUMN used_at TIMESTAMPTZ,
  ADD COLUMN revoke_reason VARCHAR(40);
UPDATE refresh_tokens SET family_id = id WHERE family_id IS NULL;
ALTER TABLE refresh_tokens ALTER COLUMN family_id SET NOT NULL;
CREATE INDEX idx_refresh_family ON refresh_tokens(family_id);

-- Append-only audit log for administrative and payment decisions.
CREATE TABLE audit_events (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 actor_type actor_type NOT NULL,
 actor_user_id UUID REFERENCES users(id),
 action VARCHAR(80) NOT NULL,
 entity_type VARCHAR(80) NOT NULL,
 entity_id UUID,
 reason VARCHAR(500),
 details JSONB NOT NULL DEFAULT '{}'::jsonb,
 trace_id VARCHAR(64),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK (actor_type NOT IN ('CUSTOMER','ADMIN') OR actor_user_id IS NOT NULL)
);
CREATE INDEX idx_audit_entity ON audit_events(entity_type, entity_id, created_at DESC);
CREATE INDEX idx_audit_created ON audit_events(created_at DESC);

CREATE FUNCTION reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$;
CREATE TRIGGER trg_audit_events_append_only
  BEFORE UPDATE OR DELETE ON audit_events
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Outbox retry/backoff.
ALTER TABLE outbox_events
  ADD COLUMN attempts INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ADD COLUMN last_error TEXT;
DROP INDEX idx_outbox_pending;
CREATE INDEX idx_outbox_pending ON outbox_events(next_attempt_at) WHERE processed_at IS NULL;

-- updated_at maintenance.
CREATE FUNCTION set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END $$;
CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_products_updated_at BEFORE UPDATE ON products
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_orders_updated_at BEFORE UPDATE ON orders
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_payments_updated_at BEFORE UPDATE ON payments
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
