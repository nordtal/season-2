-- The SMP's schema: the per-player row, the aura ledger, the milestone track's progress, graves,
-- POIs, duels and the wheel's spins. See docs/smp.md, especially "Data model".
--
-- Migrated here like every other table (docs/architecture.md#schema-ownership): the bot applies
-- this at startup and the `smp` plugin reads and writes it afterwards. The bot itself touches none
-- of these tables today - it owns the DDL, not the gameplay - and that is deliberate: the SMP's
-- world state is not steered from Discord (docs/smp.md#discord).
--
-- Every point in time is `timestamptz` and no duration here is an `interval 'N days'` - see V4's
-- header for the full reasoning; it applies unchanged to every timestamp below.
--
-- Everything hangs off `discord_user`, never off the Minecraft UUID: the UUID reaches a row
-- through `account_link`, and duplicating it would create a second answer to "whose account is
-- this" (docs/smp.md#data-model). Where docs/smp.md's ER sketch draws a key as
-- `bigint discord_user_id`, this file uses `varchar(32) discord_id`, exactly as V4 and V5 already
-- corrected the same sketch - the schema's real key has been that since V1 and a foreign key has
-- to match it.
--
-- **Nothing here defines a milestone.** The definition is the reloadable YAML file in the plugin
-- (docs/smp.md#where-a-milestone-is-defined); these tables hold *progress* only. That split is what
-- lets a milestone be appended, or a target lowered, without a migration - and it is why the
-- loader has to validate the file against these rows rather than the other way round.


-- One row per player who has ever been on the SMP, created at their first join.
--
-- `aura` is a single signed integer and it buys nothing (docs/smp.md#aura--recognition-not-currency).
-- It is prestige only: the tab list and the leaderboard board at the spawn are the whole of what it
-- does. It may go negative - deaths cost aura and a duel loss costs 10 - which is why this column
-- carries no non-negative CHECK, unlike `player_playtime.seconds`.
--
-- The last death location lives here rather than in `smp_grave` because it is a different fact: a
-- grave is an object in the world that can be emptied and eventually forgotten, while "where you
-- last died" is a built-in `/navigate` target (docs/smp.md#navigate) that has to survive the grave
-- being looted, the farm world being reset under it, and the player never going back.
CREATE TABLE smp_player
(
    discord_id               varchar(32) PRIMARY KEY
        REFERENCES discord_user (discord_id) ON DELETE CASCADE,

    -- Signed on purpose. See above.
    aura                     int         NOT NULL DEFAULT 0,

    -- Nullable as a group: a player who has not died yet has no last death. The plugin writes all
    -- four together or none of them.
    last_death_world         text,
    last_death_x             int,
    last_death_y             int,
    last_death_z             int,

    -- The hunger games winner's head start, granted by the SMP plugin on that player's first join
    -- and never again (docs/hunger-games.md#after-the-game, docs/smp.md#the-hunger-games-winners-head-start).
    --
    -- **The SMP derives the entitlement; `hunger-games` does not write it.** The winner is already
    -- recorded exactly once, in `hg_game.winner_member_id` of the DECIDED game, so a second copy of
    -- that fact here would be a second answer to "who won". What the SMP cannot derive is whether
    -- it has already paid out, and that is the only thing this column stores.
    --
    -- Decided 2026-09-01, correcting an earlier plan in which `hunger-games` booked the aura into
    -- `smp_aura_event` at the moment of the decision. That would have made the event plugin write
    -- into the SMP's tables before the SMP existed, and it would have paid a winner who never
    -- turns up for the season at all.
    hg_winner_reward_granted boolean     NOT NULL DEFAULT false,

    created                  timestamptz NOT NULL DEFAULT now(),
    updated                  timestamptz NOT NULL DEFAULT now()
);

-- The aura leaderboard board at the spawn reads the top of this ordering on every render, per
-- player and in their own language. DESC because the board is a leaderboard and nothing ever asks
-- for the bottom of it.
CREATE INDEX smp_player_aura_idx ON smp_player (aura DESC);


-- Every change to a player's aura, with the reason that caused it.
--
-- The ledger exists so that a leaderboard position can always be explained
-- (docs/smp.md#contribution-payout). That matters most for the case the design deliberately does
-- not protect against: repeatedly killing somebody drains a publicly visible number, and there is
-- no daily cap and no per-killer cooldown, so "why did I lose 40 aura overnight" has to be
-- answerable from the data rather than from memory.
--
-- `reason` is NOT CHECK-constrained, the same reasoning as `hg_event.type`, `audit_log.action` and
-- `managed_message.kind`: a new aura source must not need a migration, and docs/smp.md already
-- expects the advancement list and the death causes to be retuned from config.
CREATE TABLE smp_aura_event
(
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    discord_id varchar(32) NOT NULL
        REFERENCES discord_user (discord_id) ON DELETE CASCADE,

    -- Signed, and never zero in practice: DUEL_WIN +10, DUEL_LOSS -10, DEATH -5, DEATH_LISTED -20,
    -- CONTRIBUTION a share of an objective's pot, ADVANCEMENT 2-10, HG_WINNER the head start,
    -- ADMIN anything an admin books by hand.
    delta      int         NOT NULL,

    -- DUEL_WIN, DUEL_LOSS, DEATH, DEATH_LISTED, CONTRIBUTION, ADVANCEMENT, HG_WINNER, ADMIN, ...
    reason     varchar(32) NOT NULL,

    -- What the reason points at, where there is something to point at: an objective key, a duel id,
    -- an advancement key, a damage type. Free text on purpose - these are keys from four different
    -- namespaces and none of them is a foreign key this table should enforce.
    ref        text,

    at         timestamptz NOT NULL DEFAULT now()
);

-- One player's ledger, newest first - the only way this table is ever read.
CREATE INDEX smp_aura_event_discord_id_at_idx ON smp_aura_event (discord_id, at DESC);


-- The track's progress, one row per milestone key declared in the YAML file.
--
-- `key` is the YAML key (`waiting`, `departure`, `foothold`, ...) and is the join to the
-- definition. A key that the config no longer declares is what the loader's validation exists to
-- catch: it must refuse a change that would orphan stored progress, while explicitly permitting a
-- lowered `target` on a live objective - that is the finest of the three escape hatches
-- (docs/smp.md#when-an-objective-turns-out-to-be-impossible) and it is worth nothing if the
-- validation blocks it.
--
-- There is deliberately no ordering column. The track is linear and its order is the order of the
-- YAML file; storing it here would create a second answer that a file edit could contradict.
CREATE TABLE smp_milestone
(
    key      text PRIMARY KEY,

    -- LOCKED is not yet reachable, ACTIVE is the one milestone whose objectives are being worked
    -- on, UNLOCKED is done and paid out. Exactly one row is ACTIVE at a time; that is a rule of the
    -- engine rather than of the schema, because a partial unique index would also have to survive
    -- the moment between unlocking one milestone and activating the next.
    state    varchar(16) NOT NULL DEFAULT 'LOCKED'
        CONSTRAINT smp_milestone_state_check
            CHECK (state IN ('LOCKED', 'ACTIVE', 'UNLOCKED')),

    -- When it unlocked. NULL until it does.
    unlocked timestamptz
);


-- One objective of one milestone. All of a milestone's objectives must complete before it unlocks.
CREATE TABLE smp_objective
(
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    milestone_key text        NOT NULL
        REFERENCES smp_milestone (key) ON DELETE CASCADE,

    -- The objective's own key within its milestone, from the YAML file.
    key           text        NOT NULL,

    -- CHECK-constrained, unlike `smp_aura_event.reason`, and the difference is deliberate: the
    -- three types are a closed set by design (docs/smp.md#objective-types) because each one is a
    -- different way of *measuring* a contribution, not a different piece of content. Adding a
    -- fourth is a design change that should cost a migration; adding a new HAND_IN objective is a
    -- config edit and costs nothing.
    type          varchar(16) NOT NULL
        CONSTRAINT smp_objective_type_check
            CHECK (type IN ('HAND_IN', 'STATISTIC', 'ADVANCEMENT')),

    -- What has been collected so far, and what is needed. `target` is copied out of the YAML file
    -- at the moment the objective is created, and it is the number an admin completion pays
    -- `pot * (amount / target)` against - so lowering the target in the file and reloading has to
    -- update this column, not just the file (that is the first escape hatch), while the payout of
    -- an already-rescued objective still refers to what was actually asked for at the time.
    amount        bigint      NOT NULL DEFAULT 0
        CONSTRAINT smp_objective_amount_not_negative CHECK (amount >= 0),
    target        bigint      NOT NULL
        CONSTRAINT smp_objective_target_positive CHECK (target > 0),

    -- When it completed and paid out. NULL while it is open. Payout happens once, here, not
    -- continuously (docs/smp.md#contribution-payout).
    completed     timestamptz,

    CONSTRAINT smp_objective_key_per_milestone UNIQUE (milestone_key, key)
);


-- Who contributed how much to one objective, which is what the pot is split by.
--
-- The composite primary key is the whole story: one row per player per objective, accumulated in
-- place. It also means the 30/70 split can be computed in one pass over this table without a
-- GROUP BY over a log of individual hand-ins - the individual deliveries are not history anybody
-- has asked to keep, and keeping them would make an ADVANCEMENT objective (whose share is 1 or 0)
-- a strange special case.
CREATE TABLE smp_contribution
(
    objective_id uuid        NOT NULL
        REFERENCES smp_objective (id) ON DELETE CASCADE,

    discord_id   varchar(32) NOT NULL
        REFERENCES discord_user (discord_id) ON DELETE CASCADE,

    -- For HAND_IN and STATISTIC, how much this player delivered or accrued since the objective
    -- started. For ADVANCEMENT it is 1 - the player either earned it or has no row here at all,
    -- which is also why a player who earned it and never logs in again stays counted: progress
    -- lives here and is never recomputed (docs/smp.md#the-rules-the-content-has-to-obey).
    amount       bigint      NOT NULL DEFAULT 0
        CONSTRAINT smp_contribution_amount_not_negative CHECK (amount >= 0),

    updated      timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (objective_id, discord_id)
);


-- A public point of interest, created by a player and visible to everyone (docs/smp.md#navigate).
--
-- Names are NOT unique. Anyone may create a POI, everybody sees all of them, and two players
-- naming a place the same thing is a social problem rather than a data one; a unique constraint
-- would turn it into a confusing failure at the moment of creation. `/navigate` lists them by name
-- and picks by id.
CREATE TABLE smp_poi
(
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    name       text        NOT NULL,

    world      text        NOT NULL,
    x          int         NOT NULL,
    y          int         NOT NULL,
    z          int         NOT NULL,

    created_by varchar(32) NOT NULL
        REFERENCES discord_user (discord_id) ON DELETE CASCADE,

    created    timestamptz NOT NULL DEFAULT now()
);

-- The daily farm-world reset deletes every POI in that world (docs/smp.md#the-farm-world-reset), and
-- `/navigate` lists POIs per world. One index serves both.
CREATE INDEX smp_poi_world_idx ON smp_poi (world);


-- A death's grave: the full inventory and experience, standing in the world until somebody empties
-- it (docs/smp.md#death-and-graves).
--
-- There is no grave in the duel arena, which is why nothing here references `smp_duel`.
CREATE TABLE smp_grave
(
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    owner_id   varchar(32) NOT NULL
        REFERENCES discord_user (discord_id) ON DELETE CASCADE,

    world      text        NOT NULL,
    x          int         NOT NULL,
    y          int         NOT NULL,
    z          int         NOT NULL,

    -- The inventory, serialised by the plugin. `bytea` rather than a normalised item table because
    -- nothing ever queries *into* a grave - it is written once and read back whole - and because
    -- the only format that survives a Minecraft update intact is the platform's own.
    contents   bytea       NOT NULL,

    -- Credited in full when the grave is opened, not scaled by anything.
    experience int         NOT NULL DEFAULT 0
        CONSTRAINT smp_grave_experience_not_negative CHECK (experience >= 0),

    created    timestamptz NOT NULL DEFAULT now(),

    -- When it was emptied, and by whom. NULL while it still stands - and it stands forever, with no
    -- timer and no ownership lock, so this stays NULL for graves nobody ever walks back to.
    --
    -- `looted_by` records what our own table can honestly know. docs/smp.md struck the claim that
    -- grave-emptying is traceable *through the block log* - graves are plugin-managed inventories,
    -- and there may be no block-logging plugin running at all - but that is a statement about
    -- CoreProtect, not about this column. Killing a player and emptying their grave stays
    -- mechanically possible and socially settled; this only means the question "who took it" has an
    -- answer when somebody asks.
    looted     timestamptz,
    looted_by  varchar(32)
        REFERENCES discord_user (discord_id) ON DELETE SET NULL
);

-- The reset deletes every grave in the farm world; a player's own graves are listed for them.
CREATE INDEX smp_grave_world_idx ON smp_grave (world);
CREATE INDEX smp_grave_owner_id_created_idx ON smp_grave (owner_id, created DESC);


-- One duel on one of the two platforms at the spawn (docs/smp.md#duels).
--
-- A duel only ever *moves* aura between two players - the winner takes exactly what the loser
-- pays - so `stake` is stored once rather than as two ledger amounts that could drift apart. The
-- two `smp_aura_event` rows are still written; this column is what they are derived from.
CREATE TABLE smp_duel
(
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- One platform each, and the loadout is per type.
    type          varchar(16) NOT NULL
        CONSTRAINT smp_duel_type_check
            CHECK (type IN ('SWORD', 'BOW')),

    challenger_id varchar(32) NOT NULL
        REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    opponent_id   varchar(32) NOT NULL
        REFERENCES discord_user (discord_id) ON DELETE CASCADE,

    -- NULL only while the duel is still running.
    winner_id     varchar(32)
        REFERENCES discord_user (discord_id) ON DELETE SET NULL,

    stake         int         NOT NULL DEFAULT 10
        CONSTRAINT smp_duel_stake_not_negative CHECK (stake >= 0),

    -- RUNNING while it is being fought, DEFEAT when somebody was beaten, DISCONNECT when somebody
    -- logged out - which counts as a defeat and books the aura, because otherwise logging out is a
    -- free escape from losing.
    outcome       varchar(16) NOT NULL DEFAULT 'RUNNING'
        CONSTRAINT smp_duel_outcome_check
            CHECK (outcome IN ('RUNNING', 'DEFEAT', 'DISCONNECT')),

    started       timestamptz NOT NULL DEFAULT now(),
    ended         timestamptz,

    -- A duel that has ended has a winner and vice versa. Nothing else in this schema pairs two
    -- nullable columns this tightly, and a half-written outcome is exactly the state that would
    -- make the aura books disagree with the duel history.
    CONSTRAINT smp_duel_ended_has_winner
        CHECK ((outcome = 'RUNNING' AND ended IS NULL AND winner_id IS NULL)
            OR (outcome <> 'RUNNING' AND ended IS NOT NULL AND winner_id IS NOT NULL)),

    -- Nobody duels themselves.
    CONSTRAINT smp_duel_distinct_players CHECK (challenger_id <> opponent_id)
);

CREATE INDEX smp_duel_challenger_id_idx ON smp_duel (challenger_id);
CREATE INDEX smp_duel_opponent_id_idx ON smp_duel (opponent_id);


-- The wheel of fortune's spins (docs/smp.md#the-wheel-of-fortune): one free per day, plus extras
-- earned by contributing to objectives.
--
-- `last_free` is a `date` and not a `timestamptz`, which is the one place in this schema where a
-- calendar day is the actual unit rather than an accident - "one free spin per day" is a statement
-- about days, and comparing it to `current_date` is the whole rule. It is therefore also the one
-- value in the schema that depends on the database's time zone, and that is accepted: a server
-- whose players are in one country has one obvious day boundary.
CREATE TABLE smp_spin
(
    discord_id varchar(32) PRIMARY KEY
        REFERENCES discord_user (discord_id) ON DELETE CASCADE,

    -- Extra spins earned from contributions - staggered at the 2 / 10 / 25 % contribution shares,
    -- granted when an objective completes.
    granted    int  NOT NULL DEFAULT 0
        CONSTRAINT smp_spin_granted_not_negative CHECK (granted >= 0),

    -- How many of the granted extras have been used. The free daily spin is not counted here; it is
    -- `last_free < current_date`.
    used       int  NOT NULL DEFAULT 0
        CONSTRAINT smp_spin_used_not_negative CHECK (used >= 0),

    -- NULL until the first free spin is taken.
    last_free  date,

    CONSTRAINT smp_spin_used_within_granted CHECK (used <= granted)
);
