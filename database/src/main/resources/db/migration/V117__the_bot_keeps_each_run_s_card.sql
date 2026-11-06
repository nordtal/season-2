-- The card discord-bot posted for each run in the admin channel, so a restarted bot edits that card instead of posting
-- a second one, and posts a failed run's alert once.
--
-- This only adds.

CREATE TABLE bot_run_card
(
    run        bigint      NOT NULL CONSTRAINT bot_run_card_run_fkey REFERENCES steward_inbox (id) ON DELETE CASCADE,
    -- Empty while the alert came before the card had its message id, or before any card was posted.
    message_id varchar(32) CONSTRAINT bot_run_card_message_id_check CHECK (message_id ~ '^[0-9]{1,20}$'),
    -- Whether the alert of the failed run took the card's place.
    alerted    boolean     NOT NULL DEFAULT false,
    CONSTRAINT bot_run_card_pkey PRIMARY KEY (run)
);
COMMENT ON TABLE bot_run_card IS 'Owned by discord-bot: the card of each run in the admin channel, and whether its alert took its place.';

GRANT SELECT, INSERT, UPDATE ON bot_run_card TO ${role_discord_bot};
