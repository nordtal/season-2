-- Steward asks the SMP to set a person's aura or extra spins, and the journal keeps the value each request set.
CREATE TABLE smp_set_aura
(
    request bigint      PRIMARY KEY CONSTRAINT smp_set_aura_request_fkey REFERENCES smp_inbox (id) ON DELETE CASCADE,
    person  varchar(32) NOT NULL
        CONSTRAINT smp_set_aura_person_fkey REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    aura    integer     NOT NULL
);
COMMENT ON TABLE smp_set_aura IS 'Owned by smp, which claims its inbox: the person and the aura a SET_AURA row of smp_inbox sets.';
CREATE INDEX smp_set_aura_person ON smp_set_aura (person);

CREATE TABLE smp_set_spins
(
    request bigint      PRIMARY KEY CONSTRAINT smp_set_spins_request_fkey REFERENCES smp_inbox (id) ON DELETE CASCADE,
    person  varchar(32) NOT NULL
        CONSTRAINT smp_set_spins_person_fkey REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    spins   integer     NOT NULL CONSTRAINT smp_set_spins_spins_not_negative CHECK (spins >= 0)
);
COMMENT ON TABLE smp_set_spins IS 'Owned by smp, which claims its inbox: the person and the extra spins a SET_SPINS row of smp_inbox sets.';
CREATE INDEX smp_set_spins_person ON smp_set_spins (person);

-- Steward's Users page shows the extra spins beside the aura.
GRANT SELECT ON smp_set_aura, smp_set_spins, smp_spin TO ${role_read};
GRANT INSERT ON smp_set_aura, smp_set_spins TO ${role_steward_ui};

ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_kind_check;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_kind_check
        CHECK (kind IN ('COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE', 'PREVIEW_MESSAGE', 'CHECK_SETTINGS', 'SET_AURA',
                        'SET_SPINS'));
COMMENT ON TABLE smp_inbox IS 'Owned by smp, which claims and carries out every row. steward asks for a track action, a person''s aura or spins, an admin''s preview of a text and a try of a settings change.';

ALTER TABLE journal_entry DROP CONSTRAINT journal_entry_action_check;
ALTER TABLE journal_entry
    ADD CONSTRAINT journal_entry_action_check CHECK (action IN (
        'ADMIN_ROOT', 'GRANT_ADMIN', 'REVOKE_ADMIN', 'ENFORCE_PACK', 'EXEMPT_PACK', 'GRANT_ACCESS', 'REVOKE_ACCESS',
        'SET_PLAYTIME', 'LINK', 'UNLINK', 'SETTLE', 'SET_PHASE', 'SET_LAUNCH', 'SET_SMP_START', 'REGISTER_KEY',
        'HELD_KEY', 'RENAME_KEY', 'REMOVE_KEY', 'ADD_AUTHENTICATOR', 'USED_AUTHENTICATOR', 'REMOVE_AUTHENTICATOR',
        'FORGET_FACTORS', 'WEB_PUSH_SUBSCRIBE', 'WEB_PUSH_UNSUBSCRIBE', 'WEB_PUSH_TEST', 'SET_ALERT_PREFERENCE',
        'CONSOLE', 'SAVE_SETTINGS', 'SAVE_MESSAGES', 'ADD_PLUGIN', 'REMOVE_PLUGIN', 'REQUEST_RUN', 'CANCEL_RUN',
        'DOWNLOAD_BACKUP', 'ANNOUNCE', 'COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE', 'START_GAME', 'UNANCHORED_GRANT',
        'LINK_CODES_EXHAUSTED', 'SET_AURA', 'SET_SPINS'));

CREATE TABLE journal_aura
(
    entry uuid    PRIMARY KEY CONSTRAINT journal_aura_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    aura  integer NOT NULL
);
COMMENT ON TABLE journal_aura IS 'Owned by steward: the aura a SET_AURA asked for.';

CREATE TABLE journal_spins
(
    entry uuid    PRIMARY KEY CONSTRAINT journal_spins_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    spins integer NOT NULL CONSTRAINT journal_spins_spins_not_negative CHECK (spins >= 0)
);
COMMENT ON TABLE journal_spins IS 'Owned by steward: the extra spins a SET_SPINS asked for.';

GRANT SELECT, INSERT ON journal_aura, journal_spins TO ${role_steward_ui}, ${role_discord_bot};
