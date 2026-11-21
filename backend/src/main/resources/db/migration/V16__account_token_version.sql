-- Every access token carries the account's token version (claim "ver"), and every refresh family
-- keeps the version it was signed in under. The version moves on whenever `enabled` changes, by
-- the operator command or by hand, so no token or session from before a disable is accepted
-- after a re-enable. It never moves otherwise and cannot be set directly. Existing rows start at
-- 0, matching the families they already hold.
ALTER TABLE users
 ADD COLUMN token_version INT NOT NULL DEFAULT 0,
 ADD CONSTRAINT ck_users_token_version CHECK (token_version >= 0);
ALTER TABLE refresh_tokens
 ADD COLUMN token_version INT NOT NULL DEFAULT 0,
 ADD CONSTRAINT ck_refresh_token_version CHECK (token_version >= 0);

CREATE FUNCTION users_token_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.enabled IS DISTINCT FROM OLD.enabled THEN
    NEW.token_version = OLD.token_version + 1;
  ELSE
    NEW.token_version = OLD.token_version;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_users_token_version BEFORE UPDATE ON users
 FOR EACH ROW EXECUTE FUNCTION users_token_version();
