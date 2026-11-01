-- An admin's announcement is a document that is drafted, edited as often as wanted and then sent. Each draft holds
-- the channel of each language it goes out in, so one can go out in one language only. A send asks the bot to post
-- the languages not out yet, so a retry is one more request of the same announcement. When a language went out is
-- kept with the announcement, since its requests are purged after a while.
CREATE TABLE announcement
(
    id       bigserial   PRIMARY KEY,
    document jsonb       NOT NULL
        CONSTRAINT announcement_document_check CHECK (jsonb_typeof(document) = 'object'),
    changed  timestamptz NOT NULL,
    sent     timestamptz
);
COMMENT ON TABLE announcement IS 'Owned by steward: an admin''s announcement, the whole document as the admin wrote it, when it last changed and when it was first sent. A sent one no longer changes.';

CREATE TABLE announcement_channel
(
    announcement bigint      NOT NULL
        CONSTRAINT announcement_channel_announcement_fkey REFERENCES announcement (id) ON DELETE CASCADE,
    language     varchar(16) NOT NULL
        CONSTRAINT announcement_channel_language_check CHECK (language ~ '^[a-z]{2,8}$'),
    channel      varchar(32) NOT NULL
        CONSTRAINT announcement_channel_channel_check CHECK (channel ~ '^[0-9]{1,32}$'),
    posted       timestamptz,
    CONSTRAINT announcement_channel_pkey PRIMARY KEY (announcement, language)
);
COMMENT ON TABLE announcement_channel IS 'Owned by steward: the Discord channel an announcement goes out in for one language, and when the bot first posted it there. A language without a row is not sent.';

CREATE TABLE bot_post_announcement
(
    request      bigint PRIMARY KEY
        CONSTRAINT bot_post_announcement_request_fkey REFERENCES bot_inbox (id) ON DELETE CASCADE,
    announcement bigint NOT NULL
        CONSTRAINT bot_post_announcement_announcement_fkey REFERENCES announcement (id)
);
COMMENT ON TABLE bot_post_announcement IS 'Owned by discord-bot, which claims its inbox: the admin''s announcement a POST_ANNOUNCEMENT row of bot_inbox posts.';

CREATE TABLE bot_post_announcement_target
(
    request  bigint      NOT NULL
        CONSTRAINT bot_post_announcement_target_request_fkey
            REFERENCES bot_post_announcement (request) ON DELETE CASCADE,
    language varchar(16) NOT NULL
        CONSTRAINT bot_post_announcement_target_language_check CHECK (language ~ '^[a-z]{2,8}$'),
    channel  varchar(32) NOT NULL
        CONSTRAINT bot_post_announcement_target_channel_check CHECK (channel ~ '^[0-9]{1,32}$'),
    posted   boolean,
    CONSTRAINT bot_post_announcement_target_pkey PRIMARY KEY (request, language)
);
COMMENT ON TABLE bot_post_announcement_target IS 'Owned by discord-bot, which claims its inbox: one language of a POST_ANNOUNCEMENT row, the channel it is posted in as the request was written and, once answered, whether the bot posted it.';

ALTER TABLE bot_inbox DROP CONSTRAINT bot_inbox_kind_check;
ALTER TABLE bot_inbox
    ADD CONSTRAINT bot_inbox_kind_check
        CHECK (kind IN ('GRANT', 'REVOKE', 'UNLINK', 'SET_PLAYTIME', 'ANNOUNCE', 'POST_ALERT', 'PAYMENT_BOOKED',
                        'PREVIEW_MESSAGE', 'PREVIEW_DOCUMENT', 'POST_ANNOUNCEMENT'));
COMMENT ON TABLE bot_inbox IS 'Owned by discord-bot, which claims and carries out every row. steward asks for access changes, announcements, an alert in the admin channel, the word to a payer whose payment it booked and an admin''s preview of a text or a document, smp for its announcements.';

-- Steward drafts and sends an announcement, and the bot reads what it posts.
GRANT SELECT ON announcement, announcement_channel, bot_post_announcement, bot_post_announcement_target
    TO ${role_read};
GRANT INSERT, UPDATE, DELETE ON announcement, announcement_channel TO ${role_steward_ui};
GRANT USAGE ON SEQUENCE announcement_id_seq TO ${role_steward_ui};
GRANT INSERT ON bot_post_announcement, bot_post_announcement_target TO ${role_steward_ui};
GRANT UPDATE (posted) ON bot_post_announcement_target, announcement_channel TO ${role_discord_bot};
