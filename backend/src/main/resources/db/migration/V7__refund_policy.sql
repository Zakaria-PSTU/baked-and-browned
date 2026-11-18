-- Partial refunds: several refunds per payment, at most one open (REQUESTED or APPROVED) at a time.
-- The service keeps the sum of non-rejected refunds within the payment amount under the payment lock.
DROP INDEX uq_refund_active_per_payment;
CREATE UNIQUE INDEX uq_refund_open_per_payment
  ON refunds(payment_id) WHERE status IN ('REQUESTED','APPROVED');
-- Second approver: whoever requested a refund never approves it.
ALTER TABLE refunds
  ADD CONSTRAINT ck_refund_second_approver
    CHECK (status NOT IN ('APPROVED','COMPLETED') OR requested_by IS NULL OR decided_by <> requested_by);
-- Customer notice when a refund has been sent.
ALTER TABLE notifications DROP CONSTRAINT notifications_kind_check;
ALTER TABLE notifications
  ADD CONSTRAINT notifications_kind_check
    CHECK (kind IN ('ORDER_RECEIVED','ORDER_CONFIRMED','ORDER_DISPATCHED','ORDER_CANCELLED','ORDER_EXPIRED',
                    'REFUND_COMPLETED'));
-- Refunds never exceed the payment: non-rejected refunds together stay within its amount. The payment
-- row is locked, so concurrent inserts or updates for one payment are checked one at a time.
CREATE FUNCTION check_refund_total() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  paid NUMERIC(12,2);
  taken NUMERIC(12,2);
BEGIN
  IF NEW.status = 'REJECTED' THEN
    RETURN NEW;
  END IF;
  SELECT amount INTO paid FROM payments WHERE id = NEW.payment_id FOR UPDATE;
  SELECT coalesce(sum(amount), 0) INTO taken FROM refunds
   WHERE payment_id = NEW.payment_id AND status <> 'REJECTED' AND id <> NEW.id;
  IF taken + NEW.amount > paid THEN
    RAISE EXCEPTION 'ck_refund_within_payment: refunds of payment % would exceed its amount', NEW.payment_id
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_refund_within_payment';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_refunds_within_payment
  BEFORE INSERT OR UPDATE OF amount, status, payment_id ON refunds
  FOR EACH ROW EXECUTE FUNCTION check_refund_total();
