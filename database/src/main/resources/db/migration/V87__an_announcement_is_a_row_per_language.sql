-- An announcement is a row of its message and milestone and a row per language: the admin's words or the milestone's
-- name in that language, and whether the bot posted it. The bot renders each text when it posts.
CREATE TABLE bot_announce
(
    request   bigint      PRIMARY KEY CONSTRAINT bot_announce_request_fkey REFERENCES bot_inbox (id) ON DELETE CASCADE,
    message   varchar(32) NOT NULL CONSTRAINT bot_announce_message_check CHECK (message IN (
        'WORDS', 'MILESTONE', 'MILESTONE_BORDER', 'MILESTONE_NETHER', 'MILESTONE_END')),
    milestone text        CONSTRAINT bot_announce_milestone_fkey REFERENCES smp_milestone (key),
    CONSTRAINT bot_announce_milestone_iff_reached CHECK ((message = 'WORDS') = (milestone IS NULL))
);
COMMENT ON TABLE bot_announce IS 'Owned by discord-bot, which claims its inbox: the message an ANNOUNCE row of bot_inbox posts and the milestone it names.';
CREATE INDEX bot_announce_milestone ON bot_announce (milestone);

CREATE TABLE bot_announce_line
(
    request        bigint      NOT NULL
        CONSTRAINT bot_announce_line_request_fkey REFERENCES bot_announce (request) ON DELETE CASCADE,
    language       varchar(16) NOT NULL CONSTRAINT bot_announce_line_language_check CHECK (language ~ '^[a-z]{2,8}$'),
    words          text,
    -- The milestone's name in this language, as smp names it where the milestone was reached.
    milestone_name text,
    -- The answer: whether the text went out, false for a language without a channel the bot can write to.
    posted         boolean,
    CONSTRAINT bot_announce_line_pkey PRIMARY KEY (request, language),
    CONSTRAINT bot_announce_line_words_or_name CHECK ((words IS NULL) <> (milestone_name IS NULL))
);
COMMENT ON TABLE bot_announce_line IS 'Owned by discord-bot, which claims its inbox: one language of an ANNOUNCE row of bot_inbox, the words or name it posts and whether it was posted.';

-- An admin's words keep their text and answer. A milestone's announcement named no milestone key, so it goes.
INSERT INTO bot_announce (request, message)
SELECT id, 'WORDS' FROM bot_inbox
WHERE kind = 'ANNOUNCE'
  AND NOT EXISTS (SELECT 1 FROM jsonb_each(payload -> 'messages') AS line(language, message)
                  WHERE line.message ->> 'key' <> 'announcement.words');
INSERT INTO bot_announce_line (request, language, words, posted)
SELECT i.id, line.language, line.message -> 'args' -> 'text' ->> 'value',
       CASE WHEN i.status = 'DONE' THEN coalesce(i.outcome ->> line.language = 'POSTED', false) END
FROM bot_inbox i
JOIN bot_announce a ON a.request = i.id
CROSS JOIN LATERAL jsonb_each(i.payload -> 'messages') AS line(language, message);
DELETE FROM bot_inbox WHERE kind = 'ANNOUNCE' AND id NOT IN (SELECT request FROM bot_announce);

ALTER TABLE bot_inbox DROP CONSTRAINT bot_inbox_payload_iff_json_kind;
ALTER TABLE bot_inbox DROP CONSTRAINT bot_inbox_outcome_only_answered;
UPDATE bot_inbox SET payload = NULL, outcome = NULL WHERE kind = 'ANNOUNCE';
ALTER TABLE bot_inbox
    ADD CONSTRAINT bot_inbox_payload_iff_json_kind CHECK ((kind IN ('POST_ALERT', 'PREVIEW_MESSAGE')) = (payload IS NOT NULL));
ALTER TABLE bot_inbox
    ADD CONSTRAINT bot_inbox_outcome_only_answered CHECK (outcome IS NULL OR (status = 'DONE' AND kind = 'POST_ALERT'));
COMMENT ON COLUMN bot_inbox.outcome IS 'Whether a done POST_ALERT was posted.';

-- Steward and smp announce, and the bot writes what it posted.
GRANT SELECT ON bot_announce, bot_announce_line TO ${role_read};
GRANT INSERT ON bot_announce, bot_announce_line TO ${role_steward_ui}, ${role_smp};
GRANT UPDATE (posted) ON bot_announce_line TO ${role_discord_bot};
