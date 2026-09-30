-- The second factor: the security keys registered against Steward, and the two columns on
-- steward_session that a ceremony needs.
--
-- V19 made a session a row so that it could outlive a restart, and said in its own comment that
-- thirty days is only defensible BECAUSE a key stands in front of everything dangerous. This is
-- that key. From here on a Discord session on its own is not enough to reach the interface: an
-- account with no row in this table is sent to the setup page and nowhere else.
--
--
-- WHY THE VERIFICATION IS A COLUMN ON THE SESSION AND NOT A TABLE OF ITS OWN
--
-- `steward_session.verified_at` is "when this browser last held its key". The step-up asks whether
-- it is within five minutes; the sign-in asks whether it is set at all. A separate table would be
-- a row per session with the same lifetime, the same sweep and the same delete - and the whole
-- point of V19's argument was that a timestamp has to be readable and resettable from psql. It is
-- a column for the same reason it was not a field in a serialised blob.
--
--
-- WHY THE IN-FLIGHT CEREMONY IS A COLUMN TOO
--
-- WebAuthn is two round trips: the server hands out a challenge, the authenticator signs it, the
-- server checks the answer against the challenge IT issued. Between those the whole request has to
-- be kept somewhere - not only the challenge, because the library verifies the answer against the
-- options it produced. `steward_session` already carries exactly this shape of value in
-- `oauth_state`: a one-time thing, read and cleared in one statement, tied to one browser. A third
-- table would have been a third lifetime to sweep.
--
-- The value is the library's own JSON. It is written by the one class allowed to speak Jackson and
-- is never parsed anywhere else - see `WebAuthn` in :steward-ui.
--
-- READ THE WARNING IN SessionDao BEFORE COPYING THE PATTERN: `UPDATE ... RETURNING col` answers
-- with the NEW row, so reading-and-clearing in one statement hands back the NULL just written.
-- PostgreSQL 18 has RETURNING OLD; this deployment is on 17.11, and the old value comes out of a
-- FOR UPDATE subquery instead. That mistake cost a working sign-in once already.
-- The third column is the ceremony's own clock. A challenge that stays redeemable for the thirty
-- days the session lasts is a challenge somebody has thirty days to obtain; ten minutes is far
-- more than the two the browser gives the dialog, and far less than a session.
ALTER TABLE steward_session
    ADD COLUMN verified_at         timestamptz,
    ADD COLUMN webauthn_request    text,
    ADD COLUMN webauthn_started_at timestamptz;

COMMENT ON COLUMN steward_session.verified_at IS
    'When this browser last proved a security key. NULL means Discord confirmed who they are and '
        'nothing more. Reset it to NULL to force the key again without ending the session.';

COMMENT ON COLUMN steward_session.webauthn_request IS
    'The registration or authentication ceremony in flight, as the library''s own JSON. Cleared '
        'the moment it is redeemed, exactly like oauth_state.';

COMMENT ON COLUMN steward_session.webauthn_started_at IS
    'When that ceremony was handed out. Older than ten minutes is not answerable any more.';


-- One registered key.
--
-- A row is created by a finished registration ceremony and is never edited except for its label,
-- its signature counter and the time it was last used. Deleting one is how a key is retired, and
-- deleting every row of an account is what `steward-ui forget-factors` does on this host.
CREATE TABLE steward_credential
(
    -- The authenticator's own id for this key, as it hands it back on every assertion. Binary
    -- rather than base64url text: it is bytes, the library's type is bytes, and text would be a
    -- choice of encoding that two places would have to agree on forever.
    credential_id   bytea       NOT NULL,

    -- Who this key belongs to. The same Discord id that `steward_session.discord_id` and
    -- `audit_log.actor` carry.
    discord_id      text        NOT NULL,

    -- The COSE-encoded public key. This is the only thing that verifies a signature, and it is
    -- public - a stolen copy of this table lets nobody sign anything.
    public_key      bytea       NOT NULL,

    -- The authenticator's counter, as of the last assertion.
    --
    -- It exists to catch a CLONED key: a counter that goes backwards means two devices are
    -- answering for one credential. Many modern authenticators - every iCloud passkey among them -
    -- report 0 forever, which is allowed and which the library treats as "no counter", so this is
    -- a check that fires for hardware keys and is silent for the rest. It is worth having anyway:
    -- a YubiKey is exactly the thing somebody might try to clone.
    signature_count bigint      NOT NULL DEFAULT 0,

    -- What the person calls it. "YubiKey blau", "iPhone". Typed by whoever registers it, because
    -- an authenticator's own name for itself is either absent or a marketing string, and the point
    -- of the label is to answer "which of these two do I still have".
    label           text        NOT NULL,

    -- How this authenticator can be reached, comma-separated, as the browser reported it: usb,
    -- nfc, ble, internal, hybrid. Handed back on authentication so the browser can say "hold your
    -- key to the top of the phone" rather than offering every method it has.
    transports      text,

    -- Whether this key is one that can be backed up, and whether it currently is. NULL for an
    -- authenticator that did not say.
    --
    -- This is the difference between a passkey synced through iCloud and a YubiKey in a drawer,
    -- and it is the honest basis for the settings page's hint: losing the only key matters far
    -- more when that key is a physical object that exists once.
    backup_eligible boolean,
    backed_up       boolean,

    created_at      timestamptz NOT NULL,
    last_used_at    timestamptz,

    CONSTRAINT steward_credential_pkey PRIMARY KEY (credential_id),

    -- A label that is blank or absurd is a row whose whole purpose - being told apart from the
    -- other one - is gone.
    CONSTRAINT steward_credential_label_check
        CHECK (length(btrim(label)) BETWEEN 1 AND 64),
    CONSTRAINT steward_credential_counter_check CHECK (signature_count >= 0)
);

COMMENT ON TABLE steward_credential IS
    'One registered security key. An account with no row here cannot use Steward at all: the '
        'first sign-in forces a registration. See V20.';

COMMENT ON COLUMN steward_credential.credential_id IS
    'The authenticator''s id for this key. Public, and the lookup key on every assertion.';

COMMENT ON COLUMN steward_credential.signature_count IS
    'Clone detection. Must never go backwards; many authenticators report 0 forever, which means '
        '"no counter" rather than "counter stuck".';


-- Every account-wide question this table is asked: which keys does this person have.
--
-- It is asked on EVERY request while a session is unverified, and once per sign-in afterwards, so
-- it is not an index for a report - it is the one on the hot path. The primary key answers "is
-- this credential id known" and cannot answer "whose keys are these".
CREATE INDEX steward_credential_by_account ON steward_credential (discord_id);

COMMENT ON INDEX steward_credential_by_account IS
    'select ... from steward_credential where discord_id = ? - the gate in front of the interface.';
