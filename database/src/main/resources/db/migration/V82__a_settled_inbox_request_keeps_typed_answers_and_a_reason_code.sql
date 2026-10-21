-- The bot's access requests are rows of their kinds, and a booking names only its payment request and journal entry,
-- which hold everything the bot tells the payer.
CREATE TABLE bot_grant
(
    request bigint      PRIMARY KEY CONSTRAINT bot_grant_request_fkey REFERENCES bot_inbox (id) ON DELETE CASCADE,
    -- No foreign key: a grant may reach a member the database has not met yet.
    person  varchar(32) NOT NULL,
    days    integer     NOT NULL CONSTRAINT bot_grant_days_check CHECK (days > 0),
    -- The answer: the end of the person's access after the grant.
    until   timestamptz
);
COMMENT ON TABLE bot_grant IS 'Owned by discord-bot, which claims its inbox: the person and the days a GRANT row of bot_inbox adds, and the end of access it answered.';
CREATE INDEX bot_grant_person ON bot_grant (person);

CREATE TABLE bot_revoke
(
    request bigint      PRIMARY KEY CONSTRAINT bot_revoke_request_fkey REFERENCES bot_inbox (id) ON DELETE CASCADE,
    -- No foreign key, as for bot_grant.
    person  varchar(32) NOT NULL,
    -- The answer: the running grants it ended.
    revoked integer     CONSTRAINT bot_revoke_revoked_check CHECK (revoked >= 0)
);
COMMENT ON TABLE bot_revoke IS 'Owned by discord-bot, which claims its inbox: the person a REVOKE row of bot_inbox takes the access of, and the grants it ended.';
CREATE INDEX bot_revoke_person ON bot_revoke (person);

CREATE TABLE bot_unlink
(
    request  bigint      PRIMARY KEY CONSTRAINT bot_unlink_request_fkey REFERENCES bot_inbox (id) ON DELETE CASCADE,
    person   varchar(32) NOT NULL,
    -- The answer: whether there was a link to break.
    unlinked boolean
);
COMMENT ON TABLE bot_unlink IS 'Owned by discord-bot, which claims its inbox: the person an UNLINK row of bot_inbox unlinks, and whether a link was broken.';
CREATE INDEX bot_unlink_person ON bot_unlink (person);

CREATE TABLE bot_set_playtime
(
    request bigint      PRIMARY KEY CONSTRAINT bot_set_playtime_request_fkey REFERENCES bot_inbox (id) ON DELETE CASCADE,
    -- No foreign key, as for bot_grant.
    person  varchar(32) NOT NULL,
    seconds bigint      NOT NULL CONSTRAINT bot_set_playtime_seconds_check CHECK (seconds >= 0)
);
COMMENT ON TABLE bot_set_playtime IS 'Owned by discord-bot, which claims its inbox: the person and the total play time a SET_PLAYTIME row of bot_inbox writes.';
CREATE INDEX bot_set_playtime_person ON bot_set_playtime (person);

CREATE TABLE bot_payment_booked
(
    request bigint PRIMARY KEY CONSTRAINT bot_payment_booked_request_fkey REFERENCES bot_inbox (id) ON DELETE CASCADE,
    payment uuid   NOT NULL CONSTRAINT bot_payment_booked_payment_fkey REFERENCES payment_request (id),
    entry   uuid   NOT NULL CONSTRAINT bot_payment_booked_entry_fkey REFERENCES journal_entry (id)
);
COMMENT ON TABLE bot_payment_booked IS 'Owned by discord-bot, which claims its inbox: the payment request a PAYMENT_BOOKED row of bot_inbox tells the payer of, and the journal entry of its booking.';
CREATE INDEX bot_payment_booked_payment ON bot_payment_booked (payment);
CREATE INDEX bot_payment_booked_entry ON bot_payment_booked (entry);

INSERT INTO bot_grant (request, person, days, until)
SELECT id, payload ->> 'person', cast(payload ->> 'days' AS integer),
       CASE WHEN status = 'DONE' THEN cast(outcome ->> 'until' AS timestamptz) END
FROM bot_inbox WHERE kind = 'GRANT';
INSERT INTO bot_revoke (request, person, revoked)
SELECT id, payload ->> 'person', CASE WHEN status = 'DONE' THEN cast(outcome ->> 'revoked' AS integer) END
FROM bot_inbox WHERE kind = 'REVOKE';
INSERT INTO bot_unlink (request, person, unlinked)
SELECT id, payload ->> 'person', CASE WHEN status = 'DONE' THEN cast(outcome ->> 'unlinked' AS boolean) END
FROM bot_inbox WHERE kind = 'UNLINK';
INSERT INTO bot_set_playtime (request, person, seconds)
SELECT id, payload ->> 'person', cast(payload ->> 'seconds' AS bigint)
FROM bot_inbox WHERE kind = 'SET_PLAYTIME';
INSERT INTO bot_payment_booked (request, payment, entry)
SELECT id, cast(payload ->> 'payment' AS uuid), cast(payload ->> 'entry' AS uuid)
FROM bot_inbox WHERE kind = 'PAYMENT_BOOKED';

-- A bank request answers with the tab it made or cancelled, null when nothing was done.
ALTER TABLE bank_open_tab ADD COLUMN tab bigint;
ALTER TABLE bank_cancel_tab ADD COLUMN tab bigint;
UPDATE bank_open_tab t SET tab = cast(coalesce(i.outcome ->> 'tab', i.outcome ->> 'cancelled') AS bigint)
FROM bank_inbox i WHERE i.id = t.request AND i.status = 'DONE';
UPDATE bank_cancel_tab t SET tab = cast(i.outcome ->> 'cancelled' AS bigint)
FROM bank_inbox i WHERE i.id = t.request AND i.status = 'DONE';

-- A refusal is its reason and that reason's values, each in its own column, worded where it is shown.
CREATE TABLE bot_refusal
(
    request bigint      PRIMARY KEY CONSTRAINT bot_refusal_request_fkey REFERENCES bot_inbox (id) ON DELETE CASCADE,
    reason  varchar(32) NOT NULL CONSTRAINT bot_refusal_reason_check CHECK (reason IN ('NOT_DELIVERED'))
);
COMMENT ON TABLE bot_refusal IS 'Owned by discord-bot, which claims its inbox: why it refused a REFUSED row of bot_inbox.';

CREATE TABLE smp_refusal
(
    request bigint      PRIMARY KEY CONSTRAINT smp_refusal_request_fkey REFERENCES smp_inbox (id) ON DELETE CASCADE,
    reason  varchar(32) NOT NULL CONSTRAINT smp_refusal_reason_check CHECK (reason IN (
        'NO_ACTIVE_MILESTONE', 'NO_SUCH_OBJECTIVE', 'MILESTONE_NOT_ACTIVE', 'NOT_HERE', 'SETTINGS_REFUSED')),
    -- The objective or milestone asked for, and the milestone that is active.
    key     text,
    active  text,
    -- The service that would not start with a settings change, and what it said.
    service text,
    detail  text,
    CONSTRAINT smp_refusal_key_iff_named
        CHECK ((reason IN ('NO_SUCH_OBJECTIVE', 'MILESTONE_NOT_ACTIVE')) = (key IS NOT NULL)),
    CONSTRAINT smp_refusal_active_iff_milestone CHECK ((reason = 'MILESTONE_NOT_ACTIVE') = (active IS NOT NULL)),
    CONSTRAINT smp_refusal_service_iff_settings CHECK ((reason = 'SETTINGS_REFUSED') = (service IS NOT NULL)),
    CONSTRAINT smp_refusal_detail_iff_settings CHECK ((reason = 'SETTINGS_REFUSED') = (detail IS NOT NULL))
);
COMMENT ON TABLE smp_refusal IS 'Owned by smp, which claims its inbox: why it refused a REFUSED row of smp_inbox, with the values of that reason.';

CREATE TABLE hunger_games_refusal
(
    request bigint      PRIMARY KEY
        CONSTRAINT hunger_games_refusal_request_fkey REFERENCES hunger_games_inbox (id) ON DELETE CASCADE,
    reason  varchar(32) NOT NULL CONSTRAINT hunger_games_refusal_reason_check CHECK (reason IN (
        'WRONG_PHASE', 'NO_GAME', 'WRONG_STATE', 'BELOW_HARD_MINIMUM', 'BELOW_SOFT_MINIMUM', 'NOT_HERE',
        'SETTINGS_REFUSED')),
    -- The phase of the network and the state of the game that stood in the way.
    phase   text,
    state   text,
    -- The participants a start needs or recommends, and the ones registered.
    minimum integer,
    count   integer,
    -- The service that would not start with a settings change, and what it said.
    service text,
    detail  text,
    CONSTRAINT hunger_games_refusal_phase_iff_wrong CHECK ((reason = 'WRONG_PHASE') = (phase IS NOT NULL)),
    CONSTRAINT hunger_games_refusal_state_iff_wrong CHECK ((reason = 'WRONG_STATE') = (state IS NOT NULL)),
    CONSTRAINT hunger_games_refusal_minimum_iff_below
        CHECK ((reason IN ('BELOW_HARD_MINIMUM', 'BELOW_SOFT_MINIMUM')) = (minimum IS NOT NULL)),
    CONSTRAINT hunger_games_refusal_count_iff_below
        CHECK ((reason IN ('BELOW_HARD_MINIMUM', 'BELOW_SOFT_MINIMUM')) = (count IS NOT NULL)),
    CONSTRAINT hunger_games_refusal_service_iff_settings
        CHECK ((reason = 'SETTINGS_REFUSED') = (service IS NOT NULL)),
    CONSTRAINT hunger_games_refusal_detail_iff_settings CHECK ((reason = 'SETTINGS_REFUSED') = (detail IS NOT NULL))
);
COMMENT ON TABLE hunger_games_refusal IS 'Owned by hunger-games, which claims its inbox: why it refused a REFUSED row of hunger_games_inbox, with the values of that reason.';

-- A refusal was its reason, message key and values as JSON; the key goes, the rest moves into the columns.
INSERT INTO bot_refusal (request, reason)
SELECT id, outcome ->> 'reason' FROM bot_inbox WHERE status = 'REFUSED' AND outcome ->> 'reason' = 'NOT_DELIVERED';
INSERT INTO smp_refusal (request, reason, key, active, service, detail)
SELECT id, outcome ->> 'reason', outcome -> 'args' ->> 'key', outcome -> 'args' ->> 'active',
       outcome -> 'args' ->> 'service', outcome -> 'args' ->> 'reason'
FROM smp_inbox WHERE status = 'REFUSED';
INSERT INTO hunger_games_refusal (request, reason, phase, state, minimum, count, service, detail)
SELECT id, outcome ->> 'reason', outcome -> 'args' ->> 'phase', outcome -> 'args' ->> 'state',
       cast(outcome -> 'args' ->> 'minimum' AS integer), cast(outcome -> 'args' ->> 'count' AS integer),
       outcome -> 'args' ->> 'service', outcome -> 'args' ->> 'reason'
FROM hunger_games_inbox WHERE status = 'REFUSED';

-- Whoever asks the bot writes its kind's row; whoever answers writes the answer and the refusal.
GRANT SELECT ON bot_grant, bot_revoke, bot_unlink, bot_set_playtime, bot_payment_booked, bot_refusal, smp_refusal,
    hunger_games_refusal TO ${role_read};
GRANT INSERT ON bot_grant, bot_revoke, bot_unlink, bot_set_playtime, bot_payment_booked TO ${role_steward_ui};
GRANT UPDATE (until) ON bot_grant TO ${role_discord_bot};
GRANT UPDATE (revoked) ON bot_revoke TO ${role_discord_bot};
GRANT UPDATE (unlinked) ON bot_unlink TO ${role_discord_bot};
GRANT UPDATE (tab) ON bank_open_tab, bank_cancel_tab TO ${role_steward_ui};
GRANT INSERT ON bot_refusal TO ${role_discord_bot};
GRANT INSERT ON smp_refusal TO ${role_smp};
GRANT INSERT ON hunger_games_refusal TO ${role_hunger_games};

-- The bot keeps a payload for an announcement, an alert and a preview alone, and an outcome for what an announcement
-- and an alert answer; a failure's message is in the log only.
ALTER TABLE bot_inbox ALTER COLUMN payload DROP NOT NULL;
UPDATE bot_inbox SET payload = NULL WHERE kind NOT IN ('ANNOUNCE', 'POST_ALERT', 'PREVIEW_MESSAGE');
UPDATE bot_inbox SET outcome = NULL WHERE status <> 'DONE' OR kind NOT IN ('ANNOUNCE', 'POST_ALERT');
ALTER TABLE bot_inbox
    ADD CONSTRAINT bot_inbox_payload_iff_json_kind
        CHECK ((kind IN ('ANNOUNCE', 'POST_ALERT', 'PREVIEW_MESSAGE')) = (payload IS NOT NULL));
ALTER TABLE bot_inbox
    ADD CONSTRAINT bot_inbox_outcome_only_answered
        CHECK (outcome IS NULL OR (status = 'DONE' AND kind IN ('ANNOUNCE', 'POST_ALERT')));
COMMENT ON COLUMN bot_inbox.outcome IS 'What a done ANNOUNCE posted per language and whether a POST_ALERT was posted.';

ALTER TABLE smp_inbox DROP COLUMN outcome;
ALTER TABLE hunger_games_inbox DROP COLUMN outcome;
ALTER TABLE bank_inbox DROP COLUMN outcome;
