-- Season 2's access schema. It replaces season 1's `contribution` table outright: there is no
-- ladder of contribution tiers any more, only paid access periods bound to a Discord account.
-- See docs/access-system.md.
--
-- This file was V1__contribution.sql until 2026-08-30 and was rewritten in place rather than
-- superseded by a V2 - nothing has ever run it in production, and season 2 starts empty by rule
-- (see the workspace CLAUDE.md: nothing is migrated between seasons). Any local dev database from
-- before that date must be dropped, not migrated.
--
-- PostgreSQL dialect. gen_random_uuid() is built in from PostgreSQL 13 onwards; no extension.
--
-- Every point in time is `timestamptz`, not `timestamp`. The append rule and the expiry check are
-- evaluated by the database (see AccessDirectory), so the database's clock is the authority; a
-- naive `timestamp` would silently mean "whatever time zone the writing JVM happened to be in"
-- and an access period would grow or shrink by an hour across a DST change. Java-side these
-- columns are `java.time.Instant`.


-- Everything the bot knows about a Discord account. One row per Discord user the bot has ever
-- had a reason to write about; `member_state` and `locale` are projections of Discord state the
-- bot maintains from guild events, because the proxy cannot query Discord.
CREATE TABLE discord_user
(
    -- Discord snowflake. Text, because that is what JDA hands out; varchar(32) leaves room well
    -- past the 19 digits a snowflake needs today.
    discord_id   varchar(32) PRIMARY KEY,

    -- IETF language tag, 'en' or 'de' today. English is the default and the fallback everywhere.
    locale       varchar(8)  NOT NULL DEFAULT 'en',

    -- Guild membership as the bot last saw it. A ban does not pause a paid period: the period
    -- keeps running down and this column only decides whether the login is refused right now.
    member_state varchar(16) NOT NULL DEFAULT 'MEMBER'
        CONSTRAINT discord_user_member_state_check
            CHECK (member_state IN ('MEMBER', 'LEFT', 'BANNED')),

    -- Permanent once granted. The bot never clears it - that is what makes handing the donor role
    -- out by hand in Discord safe.
    donor        boolean     NOT NULL DEFAULT false,

    updated      timestamptz NOT NULL DEFAULT now()
);


-- The 1:1 link between a Discord account and a Minecraft account.
--
-- Both sides of the 1:1 are enforced here, by the database, and nowhere else: `discord_id` is the
-- primary key and `mc_uuid` is UNIQUE, so neither a second Minecraft account on one Discord user
-- nor a shared Minecraft account across two Discord users can be written, whatever the
-- application layer believes.
CREATE TABLE account_link
(
    discord_id varchar(32) PRIMARY KEY
        REFERENCES discord_user (discord_id) ON DELETE CASCADE,

    mc_uuid    uuid        NOT NULL UNIQUE,

    linked     timestamptz NOT NULL DEFAULT now()
);


-- A short-lived code shown to an unlinked player on the login screen, typed back into Discord.
--
-- `mc_uuid` is UNIQUE on purpose: one live code per Minecraft account. A repeated join attempt is
-- an upsert on that constraint and hands out the *same* code again, which is what makes join-spam
-- pointless without a rate limiter.
CREATE TABLE link_code
(
    code    varchar(16) PRIMARY KEY,
    mc_uuid uuid        NOT NULL UNIQUE,
    created timestamptz NOT NULL DEFAULT now(),
    expires timestamptz NOT NULL,

    CONSTRAINT link_code_expires_after_created CHECK (expires > created)
);

-- The sweep that deletes expired codes runs on this.
CREATE INDEX link_code_expires_idx ON link_code (expires);


-- One attempt to buy access: a bunq.me tab, the reference printed in its description, and what
-- was ordered. Money is stored as integer cents - exact by construction, and never a float.
CREATE TABLE payment_request
(
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- NT-XXXXXX, six random hex digits. UNIQUE because the fallback matcher looks a payment up by
    -- exactly this string scraped out of a payment description.
    reference          varchar(16) NOT NULL UNIQUE,

    discord_id         varchar(32) NOT NULL
        REFERENCES discord_user (discord_id) ON DELETE CASCADE,

    days               int         NOT NULL CHECK (days > 0),

    -- What the tab asks for, in cents. The payer can edit the amount on the bunq.me page, so this
    -- is what was requested, not what arrived - see the "pay what you get" rule in the concept.
    amount_cents       int         NOT NULL CHECK (amount_cents > 0),
    donation_cents     int         NOT NULL DEFAULT 0 CHECK (donation_cents >= 0),

    status             varchar(16) NOT NULL DEFAULT 'OPEN'
        CONSTRAINT payment_request_status_check
            CHECK (status IN ('OPEN', 'PAID', 'EXPIRED', 'CANCELLED', 'SUPERSEDED')),

    bunq_tab_id        bigint,
    share_url          text,

    -- The bunq payment that settled this request. Nullable until it is paid.
    bunq_payment_id    bigint,

    created            timestamptz NOT NULL DEFAULT now(),
    expires            timestamptz NOT NULL,
    settled            timestamptz,

    CONSTRAINT payment_request_settled_iff_paid
        CHECK ((status = 'PAID') = (settled IS NOT NULL))
);

-- The only thing that actually prevents booking one bunq payment twice. The poll loop diffs
-- against what it has already seen, but that check is read-then-write and two overlapping polls
-- would both pass it. Partial, because every unsettled request has a NULL here.
CREATE UNIQUE INDEX payment_request_bunq_payment_id_key
    ON payment_request (bunq_payment_id)
    WHERE bunq_payment_id IS NOT NULL;

-- "One open request per person", enforced rather than assumed: starting a new request has to move
-- the previous one to SUPERSEDED (and close its bunq tab) in the same transaction, or fail.
CREATE UNIQUE INDEX payment_request_one_open_per_user_key
    ON payment_request (discord_id)
    WHERE status = 'OPEN';

-- The expiry sweep and the admin views both filter on status.
CREATE INDEX payment_request_status_idx ON payment_request (status);


-- A period of access. Buying while access is still running appends a new row starting where the
-- current one ends; the rule lives in AccessDirectory#grantAccess as a single statement, never in
-- a caller.
CREATE TABLE access_grant
(
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    discord_id         varchar(32) NOT NULL
        REFERENCES discord_user (discord_id) ON DELETE CASCADE,

    valid_from         timestamptz NOT NULL,
    valid_until        timestamptz NOT NULL,

    source             varchar(16) NOT NULL
        CONSTRAINT access_grant_source_check CHECK (source IN ('PURCHASE', 'ADMIN')),

    -- Set for a PURCHASE, NULL for an ADMIN grant.
    payment_request_id uuid
        REFERENCES payment_request (id) ON DELETE SET NULL,

    -- When an admin revoked it. A revoked grant never counts, even inside its own window.
    revoked            timestamptz,

    created            timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT access_grant_positive_window CHECK (valid_until > valid_from)
);

-- One payment request can produce at most one grant. Together with the unique bunq payment id
-- above this is the second half of the double-booking guard: even a request settled twice through
-- two different code paths cannot hand out two periods.
CREATE UNIQUE INDEX access_grant_payment_request_id_key
    ON access_grant (payment_request_id)
    WHERE payment_request_id IS NOT NULL;

-- The login path's only query filters by discord_id and compares valid_until against now().
CREATE INDEX access_grant_discord_id_valid_until_idx
    ON access_grant (discord_id, valid_until);


-- Append-only record of everything a human needs to be able to reconstruct: links, unlinks, admin
-- grants and revokes, manual settlements. Append-only by discipline - nothing in the codebase
-- issues an UPDATE or DELETE against it - not by trigger; the bot's database role is the same one
-- that owns the schema, so a trigger would only be documentation with a runtime cost.
CREATE TABLE audit_log
(
    id       uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    occurred timestamptz NOT NULL DEFAULT now(),

    -- LINK, UNLINK, GRANT_ACCESS, REVOKE_ACCESS, SETTLE, ...
    action   varchar(32) NOT NULL,

    -- Discord id of the admin who caused it, or NULL when the bot acted on its own.
    actor    varchar(32),

    -- Discord id the entry is about, when there is one.
    subject  varchar(32),

    -- Minecraft account the entry is about, for link/unlink.
    mc_uuid  uuid,

    -- Free text for the human reading the admin channel.
    detail   text
);

CREATE INDEX audit_log_occurred_idx ON audit_log (occurred);
CREATE INDEX audit_log_subject_idx ON audit_log (subject);
