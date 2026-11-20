-- Notice outcomes (ADR-0027). FAILED: the SMS provider rejected the notice permanently
-- (failure_code holds its status code). EXPIRED: not sent within 24 hours of its outbox event
-- (failure_code 'EXPIRED'). Neither is retried. No backfill: existing notices are PENDING or SENT.
ALTER TYPE notification_status ADD VALUE 'FAILED';
ALTER TYPE notification_status ADD VALUE 'EXPIRED';

-- Statuses are compared as text below: PostgreSQL does not let the transaction that adds an enum
-- value use it.
ALTER TABLE notifications
 ADD COLUMN failed_at TIMESTAMPTZ,
 ADD COLUMN failure_code VARCHAR(40),
 ADD CONSTRAINT ck_notification_failure CHECK (
   (status::text IN ('FAILED','EXPIRED')) = (failed_at IS NOT NULL AND failure_code IS NOT NULL));

-- Only a PENDING notice may change: SENT, FAILED and EXPIRED are final.
CREATE FUNCTION notification_final() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status::text <> 'PENDING' THEN
    RAISE EXCEPTION 'ck_notification_final: notice % is already %', OLD.id, OLD.status
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_notification_final';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_notification_final BEFORE UPDATE ON notifications
 FOR EACH ROW EXECUTE FUNCTION notification_final();
