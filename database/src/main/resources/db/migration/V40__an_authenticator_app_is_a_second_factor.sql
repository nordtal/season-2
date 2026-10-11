-- An admin may hold an authenticator app (RFC 6238 codes) beside a security key, or instead of one.
CREATE TABLE steward_authenticator
(
    discord_id   text        PRIMARY KEY,
    -- The 160-bit secret, sealed with AES-256-GCM under steward's own key and bound to discord_id:
    -- the nonce, then the ciphertext and its tag.
    secret       bytea       NOT NULL,
    -- NULL while enrolling: the app counts only once a code it showed has been typed.
    confirmed_at timestamptz,
    -- The last time step whose code was accepted; a code is never accepted twice, nor one older than this.
    last_step    bigint,
    -- Wrong codes since wrong_since, which the rate limit counts.
    wrong_codes  integer     NOT NULL DEFAULT 0 CONSTRAINT steward_authenticator_wrong_codes_check CHECK (wrong_codes >= 0),
    wrong_since  timestamptz,
    created_at   timestamptz NOT NULL,
    last_used_at timestamptz
);
COMMENT ON TABLE steward_authenticator IS 'Owned by steward-ui: one account''s authenticator app, a second factor like steward_credential. An unconfirmed row is an enrolment and counts for nothing.';

GRANT SELECT, INSERT, UPDATE, DELETE ON steward_authenticator TO ${role_steward_ui};
