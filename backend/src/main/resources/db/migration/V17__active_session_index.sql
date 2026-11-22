-- Every authenticated request now asks whether the access token's session (refresh family) is
-- still live: has at least one refresh token that is not revoked (ADR-0032). A revoked family has
-- none, so a partial index answers that with one probe, and keeps it cheap for a revoked family
-- too, whose rows can number in the thousands. No data changes.
CREATE INDEX idx_refresh_family_active ON refresh_tokens (family_id) WHERE revoked_at IS NULL;
