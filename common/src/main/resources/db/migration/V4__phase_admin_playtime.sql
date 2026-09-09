-- The phase model's schema: the phase row, the admin flag it is authorised by, and the play-time
-- counter the proxy writes.
--
-- Every point in time is `timestamptz`, for the reason V1 gives: the database's clock is the
-- authority.
--
-- **No duration in this file is an `interval 'N days'`, and none ever will be.** Adding one to a
-- `timestamptz` is calendar arithmetic in the session's time zone, which the JDBC driver takes from
-- the writing JVM's default, so the same value would differ between hosts and across a DST change.
-- `player_playtime` therefore counts seconds as a bigint.


-- The current season phase. Exactly one row, ever.
--
-- The singleton is enforced by the schema and not by convention: `id` is a boolean primary key that
-- a CHECK pins to `true`, so a second row either repeats the primary key or fails the CHECK. This
-- row decides who may join the network, so the rule cannot live in application code.
--
-- The row is seeded here rather than created on first use, so every reader is a plain SELECT and
-- every writer a plain UPDATE.
--
-- There is deliberately no `changed_by` column: who switched the phase is recorded in `audit_log`
-- by the same single statement that performs the switch.
CREATE TABLE season_phase
(
    id      boolean PRIMARY KEY DEFAULT true
        CONSTRAINT season_phase_singleton CHECK (id),

    -- The names are the SeasonPhase enum constants, exactly. The CHECK is what stops a typo in a
    -- hand-written emergency UPDATE from putting a value here that no process can read.
    phase   varchar(16) NOT NULL
        CONSTRAINT season_phase_phase_check
            CHECK (phase IN ('PRE_EVENT', 'START_EVENT', 'SMP', 'MAINTENANCE')),

    updated timestamptz NOT NULL DEFAULT now()
);

INSERT INTO season_phase (phase)
VALUES ('PRE_EVENT');


-- Who is an admin, mirrored from the Discord admin role the way `locale`, `member_state` and
-- `donor` already are.
--
-- It sits on `discord_user` and not in a config file so that every process reads it with the query
-- it already makes: the proxy on the login path, the plugins at join. An admin is appointed in
-- Discord and is an admin everywhere - there is no second admin list.
--
-- `NOT NULL DEFAULT false`: a user the mirror has never run for is not an admin, which is the safe
-- way round. Unlike `donor` this flag IS cleared again, because it is a permission and not an
-- acknowledgement.
ALTER TABLE discord_user
    ADD COLUMN admin boolean NOT NULL DEFAULT false;


-- Network-wide online time, in seconds.
--
-- **No `smp_` prefix, on purpose.** That prefix marks a table the SMP plugin owns, and this one is
-- written by `network-control`: only the proxy sees a session across servers. The SMP is the
-- biggest reader, but reading is not owning.
--
-- Keyed by `discord_id` and not by the Minecraft UUID: the UUID reaches this row through
-- `account_link`, and storing it again would create a second answer to "whose account is this".
--
-- `seconds bigint`, not an `interval`: see the note at the top of this file.
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
