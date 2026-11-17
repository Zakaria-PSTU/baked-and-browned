-- One "current" payment per order; retries supersede the previous attempt.
ALTER TABLE payments
  ADD COLUMN superseded_at TIMESTAMPTZ,
  ADD COLUMN verified_by UUID REFERENCES users(id),
  ADD COLUMN verified_at TIMESTAMPTZ,
  ADD COLUMN verification_note VARCHAR(500),
  ADD CONSTRAINT ck_payment_verification_complete
    CHECK ((verified_by IS NULL AND verified_at IS NULL)
        OR (verified_by IS NOT NULL AND verified_at IS NOT NULL AND verification_note IS NOT NULL));
CREATE UNIQUE INDEX uq_payment_current ON payments(order_id) WHERE superseded_at IS NULL;

-- Customer-submitted manual payment evidence. Never proof of payment by itself.
CREATE TYPE manual_submission_status AS ENUM ('SUBMITTED','VERIFIED','REJECTED');
CREATE TABLE manual_payment_submissions (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 payment_id UUID NOT NULL REFERENCES payments(id),
 submitted_by UUID NOT NULL REFERENCES users(id),
 provider VARCHAR(20) NOT NULL CHECK (provider IN ('BANK_TRANSFER','BKASH','NAGAD','ROCKET','OTHER')),
 transaction_reference VARCHAR(100) NOT NULL,
 proof_storage_key VARCHAR(255),
 proof_content_type VARCHAR(100),
 proof_size_bytes INT,
 proof_sha256 CHAR(64),
 status manual_submission_status NOT NULL DEFAULT 'SUBMITTED',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK (proof_storage_key IS NULL
     OR (proof_content_type IN ('image/jpeg','image/png','application/pdf')
         AND proof_size_bytes BETWEEN 1 AND 5242880
         AND proof_sha256 IS NOT NULL))
);
CREATE INDEX idx_manual_submissions_payment ON manual_payment_submissions(payment_id);
CREATE UNIQUE INDEX uq_manual_submission_open
  ON manual_payment_submissions(payment_id) WHERE status = 'SUBMITTED';
-- A transaction reference cannot back two payments unless rejected.
CREATE UNIQUE INDEX uq_manual_reference
  ON manual_payment_submissions(provider, transaction_reference) WHERE status <> 'REJECTED';

-- Refund workflow: REQUESTED -> APPROVED/REJECTED -> COMPLETED (full refunds only in MVP).
CREATE TYPE refund_status AS ENUM ('REQUESTED','APPROVED','REJECTED','COMPLETED');
CREATE TABLE refunds (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 payment_id UUID NOT NULL REFERENCES payments(id),
 order_id UUID NOT NULL REFERENCES orders(id),
 amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
 currency CHAR(3) NOT NULL DEFAULT 'BDT' CHECK (currency = 'BDT'),
 status refund_status NOT NULL DEFAULT 'REQUESTED',
 request_reason VARCHAR(500) NOT NULL,
 requested_by_type actor_type NOT NULL,
 requested_by UUID REFERENCES users(id),
 decided_by UUID REFERENCES users(id),
 decided_at TIMESTAMPTZ,
 decision_note VARCHAR(500),
 external_reference VARCHAR(150),
 completed_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK (status = 'REQUESTED'
     OR (decided_by IS NOT NULL AND decided_at IS NOT NULL AND decision_note IS NOT NULL)),
 CHECK (status <> 'COMPLETED' OR (external_reference IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_refund_active_per_payment
  ON refunds(payment_id) WHERE status <> 'REJECTED';
CREATE INDEX idx_refunds_status ON refunds(status, created_at);
CREATE TRIGGER trg_refunds_updated_at BEFORE UPDATE ON refunds
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Reconciliation for late successes, amount mismatches and ambiguous outcomes.
CREATE TYPE reconciliation_case_type AS ENUM ('LATE_SUCCESS','AMOUNT_MISMATCH','AMBIGUOUS_STATUS');
CREATE TYPE reconciliation_case_status AS ENUM ('OPEN','RESOLVED');
CREATE TABLE payment_reconciliation_cases (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 payment_id UUID NOT NULL REFERENCES payments(id),
 order_id UUID NOT NULL REFERENCES orders(id),
 case_type reconciliation_case_type NOT NULL,
 status reconciliation_case_status NOT NULL DEFAULT 'OPEN',
 details JSONB NOT NULL DEFAULT '{}'::jsonb,
 resolution VARCHAR(20) CHECK (resolution IN ('REINSTATED','REFUND_REQUESTED','NO_ACTION')),
 resolved_by UUID REFERENCES users(id),
 resolved_at TIMESTAMPTZ,
 resolution_note VARCHAR(500),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK (status = 'OPEN'
     OR (resolution IS NOT NULL AND resolved_by IS NOT NULL
         AND resolved_at IS NOT NULL AND resolution_note IS NOT NULL))
);
CREATE UNIQUE INDEX uq_reconciliation_open
  ON payment_reconciliation_cases(payment_id, case_type) WHERE status = 'OPEN';
