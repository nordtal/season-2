-- steward books a payment in one transaction: the request is paid, the access it buys is appended, the donor flag
-- is set, the journal line is written and the bot is asked to tell the payer. The bot only reacts, and never
-- settles anything again.

-- A request the last release matched and its bot had not booked yet is matched again, and booked, by steward.
UPDATE payment_request
SET bunq_payment_id = NULL, matched_cents = NULL, matched_by = NULL
WHERE status = 'OPEN' AND matched_cents IS NOT NULL;

COMMENT ON TABLE payment_request IS 'Owned by discord-bot, which opens, reselects and closes a request for the wish to buy and asks the bank''s inbox for its tab. steward writes the bank side (the tab, its failure, its cancellation) and books it.';
COMMENT ON TABLE access_grant IS 'Owned by discord-bot for an admin''s grant and steward for a booked payment: one access period. A new period is appended after the last one, which the database clock decides.';
GRANT INSERT ON access_grant TO ${role_steward_ui};

-- A payment that needs a look is an admin alert steward raises once per bank payment. Every notice there was
-- becomes that alert, routed when the bot had posted it, so none is told twice.
INSERT INTO admin_alert (raised, raised_by, type, level, subject, title, detail, path, source, routed)
SELECT reported, 'steward', 'PAYMENT', 'DOWN', 'payment', 'A payment needs a look', coalesce(detail, reason),
       '/payments', 'payment:' || bunq_payment_id, posted
FROM payment_notice
ON CONFLICT (source) DO NOTHING;
DROP TABLE payment_notice;

-- The bot is told of a booking, and no longer asked to make one.
DELETE FROM bot_inbox WHERE kind = 'SETTLE';
ALTER TABLE bot_inbox DROP CONSTRAINT bot_inbox_kind_check;
ALTER TABLE bot_inbox
    ADD CONSTRAINT bot_inbox_kind_check
        CHECK (kind IN ('GRANT', 'REVOKE', 'UNLINK', 'SET_PLAYTIME', 'RELOAD_MESSAGES', 'ANNOUNCE', 'POST_ALERT',
                        'PAYMENT_BOOKED'));
COMMENT ON TABLE bot_inbox IS 'Owned by discord-bot, which claims and carries out every row. steward asks for access changes, announcements, a reload of the messages, an alert in the admin channel and the word to a payer whose payment it booked, smp for its announcements.';

-- The price list is the network's: the bot offers it and steward books by it.
UPDATE setting_override
SET service = 'network', name = 'prices'
WHERE service = 'discord-bot' AND name = 'access' AND path IN ('tiers', 'donation-cents');
