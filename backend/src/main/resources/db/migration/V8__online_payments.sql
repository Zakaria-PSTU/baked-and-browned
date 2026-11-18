-- Online payments: the hosted checkout of the current session, so an open session is reused,
-- and when a gateway-verified payment succeeded (manual and COD use verified_at).
ALTER TABLE payments
  ADD COLUMN provider_checkout_url VARCHAR(2048),
  ADD COLUMN captured_at TIMESTAMPTZ,
  ADD CONSTRAINT ck_payment_session_checkout
    CHECK (provider_checkout_url IS NULL OR provider_session_id IS NOT NULL);
