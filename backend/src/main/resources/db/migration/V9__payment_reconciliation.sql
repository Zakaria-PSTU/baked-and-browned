-- Reconciliation worker: when the gateway was last asked about a payment, and why the last ask
-- failed (NULL when it succeeded), so checks back off and unresolved payments surface as cases.
ALTER TABLE payments
  ADD COLUMN gateway_checked_at TIMESTAMPTZ,
  ADD COLUMN gateway_check_error VARCHAR(200);
CREATE INDEX idx_payments_online_unsettled ON payments(created_at)
  WHERE method = 'ONLINE' AND provider_session_id IS NOT NULL AND superseded_at IS NULL
    AND status IN ('PENDING', 'CANCELLED');
CREATE INDEX idx_reconciliation_cases_status ON payment_reconciliation_cases(status, created_at);
