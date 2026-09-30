-- The SMP's schema: the per-player row, the aura ledger, the milestone track's progress, graves,
-- POIs, duels and the wheel's spins. The bot applies it at startup and the `smp` plugin reads and
-- writes it afterwards; the bot owns the DDL, not the gameplay.
--
-- Every point in time is `timestamptz` and no duration here is an `interval` - see V4's header.
--
-- Everything hangs off `discord_user`, never off the Minecraft UUID: the UUID reaches a row through
-- `account_link`, and duplicating it would create a second answer to "whose account is this".
--
-- **Nothing here defines a milestone.** The definition is the reloadable YAML file in the plugin;
-- these tables hold progress only. That split is what lets a milestone be appended, or a target
-- lowered, without a migration, and it is why the loader validates the file against these rows.


-- One row per player who has ever been on the SMP, created at their first join.
--
-- `aura` buys nothing - it is prestige only - and it may go negative, which is why it carries no
-- non-negative CHECK unlike `player_playtime.seconds`.
--
-- The last death location lives here rather than in `smp_grave` because it is a different fact: a
-- grave can be emptied and forgotten, while "where you last died" is a `/navigate` target that has
-- to survive the grave being looted and the farm world being reset under it.
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
    -- and never again.
    --
    -- **The SMP derives the entitlement; `hunger-games` does not write it.** The winner is already
    -- recorded once, in `hg_game.winner_member_id`, so a second copy here would be a second answer
    -- to "who won". Whether it has already paid out is the only thing this column stores.
    hg_winner_reward_granted boolean     NOT NULL DEFAULT false,

    created                  timestamptz NOT NULL DEFAULT now(),
    updated                  timestamptz NOT NULL DEFAULT now()
);

-- The aura leaderboard board at the spawn reads the top of this ordering on every render. DESC
-- because nothing ever asks for the bottom of it.
CREATE INDEX smp_player_aura_idx ON smp_player (aura DESC);


-- Every change to a player's aura, with the reason that caused it, so that a leaderboard position
-- can always be explained. There is deliberately no daily cap and no per-killer cooldown, so "why
-- did I lose 40 aura overnight" has to be answerable from the data rather than from memory.
--
-- `reason` is NOT CHECK-constrained: a new aura source must not need a migration.
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
-- `key` is the YAML key and the join to the definition. The loader's validation must refuse a change
-- that would orphan stored progress, while still permitting a lowered `target` on a live objective.
--
-- There is deliberately no ordering column: the track's order is the order of the YAML file, and
-- storing it here would create a second answer a file edit could contradict.
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

    -- CHECK-constrained, unlike `smp_aura_event.reason`: the three types are a closed set, because
    -- each is a different way of measuring a contribution rather than a different piece of content.
    -- A fourth is a design change and should cost a migration.
    type          varchar(16) NOT NULL
        CONSTRAINT smp_objective_type_check
            CHECK (type IN ('HAND_IN', 'STATISTIC', 'ADVANCEMENT')),

    -- What has been collected so far, and what is needed. `target` is copied out of the YAML file
    -- when the objective is created and is what an admin completion pays `pot * (amount / target)`
    -- against, so lowering the target in the file has to update this column and not only the file.
    amount        bigint      NOT NULL DEFAULT 0
        CONSTRAINT smp_objective_amount_not_negative CHECK (amount >= 0),
    target        bigint      NOT NULL
        CONSTRAINT smp_objective_target_positive CHECK (target > 0),

    -- When it completed and paid out. NULL while it is open; payout happens once, not
    -- continuously.
    completed     timestamptz,

    CONSTRAINT smp_objective_key_per_milestone UNIQUE (milestone_key, key)
);


-- Who contributed how much to one objective, which is what the pot is split by. One row per player
-- per objective, accumulated in place: individual deliveries are not history anybody asked to keep,
-- and keeping them would make an ADVANCEMENT objective a strange special case.
CREATE TABLE smp_contribution
(
    objective_id uuid        NOT NULL
        REFERENCES smp_objective (id) ON DELETE CASCADE,

    discord_id   varchar(32) NOT NULL
        REFERENCES discord_user (discord_id) ON DELETE CASCADE,

    -- For HAND_IN and STATISTIC, how much this player delivered or accrued since the objective
    -- started. For ADVANCEMENT it is 1 - the player either earned it or has no row at all - which
    -- is why somebody who earned it and never logs in again stays counted: progress is never
    -- recomputed.
    amount       bigint      NOT NULL DEFAULT 0
        CONSTRAINT smp_contribution_amount_not_negative CHECK (amount >= 0),

    updated      timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (objective_id, discord_id)
);


-- A public point of interest, created by a player and visible to everyone.
--
-- Names are NOT unique: two players naming a place the same thing is a social problem rather than a
-- data one, and a unique constraint would turn it into a confusing failure at creation time.
-- `/navigate` lists them by name and picks by id.
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

-- The daily farm-world reset deletes every POI in that world, and `/navigate` lists POIs per world.
CREATE INDEX smp_poi_world_idx ON smp_poi (world);


-- A death's grave: the full inventory and experience, standing in the world until somebody empties
-- it. There is no grave in the duel arena, which is why nothing here references `smp_duel`.
CREATE TABLE smp_grave
(
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    owner_id   varchar(32) NOT NULL
        REFERENCES discord_user (discord_id) ON DELETE CASCADE,

    world      text        NOT NULL,
    x          int         NOT NULL,
    y          int         NOT NULL,
    z          int         NOT NULL,

    -- The inventory, serialised by the plugin. `bytea` rather than a normalised item table: nothing
    -- ever queries into a grave, and the only format that survives a Minecraft update intact is the
    -- platform's own.
    contents   bytea       NOT NULL,

    -- Credited in full when the grave is opened, not scaled by anything.
    experience int         NOT NULL DEFAULT 0
        CONSTRAINT smp_grave_experience_not_negative CHECK (experience >= 0),

    created    timestamptz NOT NULL DEFAULT now(),

    -- When it was emptied, and by whom. NULL while it still stands - and it stands forever, with no
    -- timer and no ownership lock, so this stays NULL for graves nobody ever walks back to.
    -- Emptying somebody else's grave stays possible; this only means "who took it" has an answer.
    looted     timestamptz,
    looted_by  varchar(32)
        REFERENCES discord_user (discord_id) ON DELETE SET NULL
);

-- The reset deletes every grave in the farm world; a player's own graves are listed for them.
CREATE INDEX smp_grave_world_idx ON smp_grave (world);
CREATE INDEX smp_grave_owner_id_created_idx ON smp_grave (owner_id, created DESC);


-- One duel on one of the two platforms at the spawn.
--
-- A duel only moves aura between two players - the winner takes exactly what the loser pays - so
-- `stake` is stored once rather than as two ledger amounts that could drift apart.
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

    -- A duel that has ended has a winner and vice versa: a half-written outcome would make the aura
    -- books disagree with the duel history.
    CONSTRAINT smp_duel_ended_has_winner
        CHECK ((outcome = 'RUNNING' AND ended IS NULL AND winner_id IS NULL)
            OR (outcome <> 'RUNNING' AND ended IS NOT NULL AND winner_id IS NOT NULL)),

    -- Nobody duels themselves.
    CONSTRAINT smp_duel_distinct_players CHECK (challenger_id <> opponent_id)
);

CREATE INDEX smp_duel_challenger_id_idx ON smp_duel (challenger_id);
CREATE INDEX smp_duel_opponent_id_idx ON smp_duel (opponent_id);


-- The wheel of fortune's spins: one free per day, plus extras earned by contributing to objectives.
--
-- `last_free` is a `date` and not a `timestamptz` - the one place in this schema where a calendar
-- day is genuinely the unit, and therefore the one value that depends on the database's time zone.
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
