-- A refund requested by an ADMIN or a CUSTOMER names the user who asked. Without it,
-- ck_refund_second_approver could not tell whether the approver is the requester. SYSTEM and
-- GATEWAY requests have no user. Added without NOT VALID: an existing row breaking the rule fails
-- the migration so that it is fixed by hand rather than hidden.
ALTER TABLE refunds
 ADD CONSTRAINT ck_refund_requester_known
   CHECK (requested_by_type NOT IN ('ADMIN','CUSTOMER') OR requested_by IS NOT NULL);
