-- Absolute session lifetime (ADR-0025): each refresh family ends at family_expires_at, set at login
-- to login time + the role's absolute lifetime and copied to every successor. A token's own expiry
-- (now + the role's idle lifetime at issue) never passes it.
ALTER TABLE refresh_tokens ADD COLUMN family_expires_at TIMESTAMPTZ;

-- Existing families end their role's absolute lifetime after their first token (the login): 30 days
-- for customers, 12 hours for admins, the defaults of app.security.sessions at this migration. The
-- cleanup job deletes only whole families, so the first token of every family is still present.
UPDATE refresh_tokens t
SET family_expires_at = f.started_at
    + CASE WHEN u.role = 'ADMIN' THEN interval '12 hours' ELSE interval '30 days' END
FROM (SELECT family_id, min(created_at) AS started_at FROM refresh_tokens GROUP BY family_id) f,
     users u
WHERE t.family_id = f.family_id AND u.id = t.user_id;

-- Tokens that would outlive their family end with it; admin sessions older than 12 hours are
-- refused at their next refresh.
UPDATE refresh_tokens SET expires_at = family_expires_at WHERE expires_at > family_expires_at;

ALTER TABLE refresh_tokens
 ALTER COLUMN family_expires_at SET NOT NULL,
 ADD CONSTRAINT ck_refresh_within_family CHECK (expires_at <= family_expires_at);
