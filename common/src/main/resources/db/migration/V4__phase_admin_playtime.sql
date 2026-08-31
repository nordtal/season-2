-- The phase model's schema: the phase row, the admin flag it is authorised by, and the play-time
-- counter the proxy writes. See docs/season-phases.md and docs/architecture.md#schema-ownership.
--
-- One migration rather than three, because the three arrived together and none of them has ever
-- been applied anywhere. V1, V2 and V3 are NOT touched: they have been applied to local databases
-- and Flyway validates the checksum of an applied migration - rewriting one in place (as stage A
-- did to V1) is only safe while nothing anywhere has run it.
--
-- Every point in time is `timestamptz` for the reason V1 gives at length: the database's clock is
-- the authority and a naive `timestamp` would mean "whatever time zone the writing JVM happened to
-- be in".
--
-- **No duration in this file is an `interval 'N days'`, and none ever will be.** Adding
-- `interval 'N days'` to a `timestamptz` is *calendar* arithmetic evaluated in the session's time
-- zone, which the JDBC driver takes from the JVM's default - so the same value would differ
-- between the bot's host and the proxy's host, and would gain or lose an hour across a DST change.
-- V1 learned that the hard way (`AccessDao#grantAccess` builds its interval from hours; the
-- regression is `AccessDirectoryIntegrationTest#aDayIsExactlyTwentyFourHoursEvenAcrossADaylight...`).
-- `player_playtime` below therefore counts **seconds as a bigint**, not an `interval`, and nothing
-- here does date arithmetic at all.


-- The current season phase. Exactly one row, ever.
--
-- The singleton is enforced by the schema and not by convention: `id` is a boolean primary key
-- that a CHECK pins to `true`, so a second row either repeats the primary key (when it takes the
-- default) or fails the CHECK (when it does not). There is no third option, and no application
-- code has to remember the rule. A "conventional" singleton - one row nobody is supposed to add a
-- second of - is exactly the kind of rule that survives until the first `INSERT` written in a
-- hurry during an outage, and this row decides who may join the network.
--
-- The row is seeded here rather than created on first use, so every reader can be a plain SELECT
-- and every writer a plain UPDATE; `PRE_EVENT` is the season's start state (docs/season-phases.md,
-- the `[*] --> PRE_EVENT` edge).
--
-- There is deliberately no `changed_by` column: who switched the phase is recorded in `audit_log`,
-- by the same single statement that performs the switch (see PhaseDao#switchPhase in :common), and
-- a second copy of that fact here would be a second thing to keep in step.
CREATE TABLE season_phase
(
    id      boolean PRIMARY KEY DEFAULT true
        CONSTRAINT season_phase_singleton CHECK (id),

    -- The names are the SeasonPhase enum constants, exactly. The CHECK is what stops a typo in a
    -- hand-written emergency UPDATE - the documented last resort when the bot and the proxy are
    -- both down - from putting a value in here that no process can read.
    phase   varchar(16) NOT NULL
        CONSTRAINT season_phase_phase_check
            CHECK (phase IN ('PRE_EVENT', 'START_EVENT', 'SMP', 'MAINTENANCE')),

    updated timestamptz NOT NULL DEFAULT now()
);

INSERT INTO season_phase (phase)
VALUES ('PRE_EVENT');


-- Who is an admin, mirrored from the Discord admin role exactly the way `locale`, `member_state`
-- and `donor` already are (docs/season-phases.md#how-an-admin-is-recognised).
--
-- It sits on `discord_user` and not in a config file so that every process reads it with the query
-- it already makes: the proxy on the login path, the plugins at join. An admin is appointed in
-- Discord and is an admin everywhere. A UUID list in `gate.yml` and LuckPerms were both considered
-- and rejected - the first goes stale in several places at once, the second adds a third truth
-- with a sync cycle.
--
-- `NOT NULL DEFAULT false` follows `donor`: a user the mirror has never run for is not an admin,
-- which is the safe way round. Unlike `donor` this flag IS cleared again - losing the Discord role
-- loses the flag - because it is a permission and not an acknowledgement.
ALTER TABLE discord_user
    ADD COLUMN admin boolean NOT NULL DEFAULT false;


-- Network-wide online time, in seconds.
--
-- **No `smp_` prefix, on purpose.** The prefix marks a table the SMP plugin owns, and this one is
-- written by `network-control`: only the proxy sees a session across servers, a backend sees just
-- its own slice (docs/architecture.md#schema-ownership, docs/smp.md#prestige--a-crest-earned-by-time).
-- The SMP is the biggest reader - the prestige crest falls out of these seconds - but reading is
-- not owning, and an `smp_` prefix on a table the SMP does not write would be a lie about who to
-- go to when the number is wrong.
--
-- Keyed by `discord_id` like every other table here, not by the Minecraft UUID: the UUID reaches
-- this row through `account_link`, and storing it again would create a second answer to "whose
-- account is this". (docs/smp.md's ER sketch draws this key as `bigint discord_user_id`; the
-- schema's actual key has been `varchar(32) discord_id` since V1 and the foreign key has to match
-- it.)
--
-- `seconds bigint`, not an `interval`: see the note at the top of this file. It also makes the
-- accumulate-on-disconnect write a plain addition that two proxies could not disagree about.
CREATE TABLE player_playtime
(
    discord_id varchar(32) PRIMARY KEY
        REFERENCES discord_user (discord_id) ON DELETE CASCADE,

    -- Total online time across the whole network. AFK time counts on purpose: prestige measures
    -- presence, not effort, which is why play time is not an aura source.
    seconds    bigint      NOT NULL DEFAULT 0
        CONSTRAINT player_playtime_seconds_not_negative CHECK (seconds >= 0),

    -- When the proxy last flushed. It writes on disconnect and periodically in between, so a crash
    -- costs minutes rather than a whole session.
    updated    timestamptz NOT NULL DEFAULT now()
);
