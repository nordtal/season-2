-- Message overrides live in the database, network-wide. An admin's texts for one key of a packaged bundle in one
-- language replace the packaged texts in every process that loads that bundle; a process re-reads on nordtal_messages.

CREATE TABLE message_override
(
    -- The packaged bundle, the directory under messages/ in the jar, such as smp or paper-common.
    bundle     varchar(32)  NOT NULL CONSTRAINT message_override_bundle_check CHECK (bundle ~ '^[a-z][a-z0-9-]*$'),
    key        varchar(128) NOT NULL CONSTRAINT message_override_key_check CHECK (key ~ '^[A-Za-z0-9][A-Za-z0-9_.-]*$'),
    -- A language tag of the network's languages, such as de.
    language   varchar(16)  NOT NULL CONSTRAINT message_override_language_check CHECK (language ~ '^[a-z]{2,3}$'),
    -- One of several texts for the key, one chosen at random; a key with one text has the variant 0 only.
    variant    smallint     NOT NULL CONSTRAINT message_override_variant_check CHECK (variant >= 0),
    text       text         NOT NULL,
    -- The SHA-256 of the packaged texts this override replaced, in hex, or NULL where the language had none; a
    -- release that changes the packaged texts changes the hash.
    replaced   char(64)
        CONSTRAINT message_override_replaced_check CHECK (replaced ~ '^[0-9a-f]{64}$'),
    actor_kind varchar(16)  NOT NULL
        CONSTRAINT message_override_actor_kind_check CHECK (actor_kind IN ('PERSON', 'STEWARD', 'HOST')),
    actor_id   varchar(32),
    changed    timestamptz  NOT NULL,
    CONSTRAINT message_override_pkey PRIMARY KEY (bundle, key, language, variant),
    CONSTRAINT message_override_actor_id_iff_person CHECK ((actor_kind = 'PERSON') = (actor_id IS NOT NULL))
);
COMMENT ON TABLE message_override IS 'Owned by steward, where an admin overrides a message: one row per bundle, key, language and variant. Every process reads the rows of the bundles it loads.';

GRANT SELECT ON message_override TO ${role_read};
GRANT INSERT, UPDATE, DELETE ON message_override TO ${role_steward_ui};

-- Every process re-reads its overrides on nordtal_messages, so nobody asks the bot to reload its messages any more.
DELETE FROM bot_inbox WHERE kind = 'RELOAD_MESSAGES';
ALTER TABLE bot_inbox DROP CONSTRAINT bot_inbox_kind_check;
ALTER TABLE bot_inbox
    ADD CONSTRAINT bot_inbox_kind_check
        CHECK (kind IN ('GRANT', 'REVOKE', 'UNLINK', 'SET_PLAYTIME', 'ANNOUNCE', 'POST_ALERT', 'PAYMENT_BOOKED'));
COMMENT ON TABLE bot_inbox IS 'Owned by discord-bot, which claims and carries out every row. steward asks for access changes, announcements, an alert in the admin channel and the word to a payer whose payment it booked, smp for its announcements.';
