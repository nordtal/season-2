-- The hunger games start event's schema: one game, its teams, their members, and the event log a
-- kill tiebreaker and a post-game evaluation both read from. See docs/hunger-games.md, especially
-- "Data model".
--
-- Migrated here, like every other table (docs/architecture.md#schema-ownership): the bot applies
-- this at startup, and both the bot (registration) and the `hunger-games` plugin (game state) read
-- and write it afterwards.
--
-- Every point in time is `timestamptz`, and no duration in this file is an `interval 'N days'` -
-- see V4's header for why; the same reasoning applies to every timestamp below.
--
-- Everything hangs off `discord_user`, never off the Minecraft UUID directly - the UUID reaches a
-- row through `account_link`, exactly like every other table in this schema, and duplicating it
-- here would create a second answer to "whose account is this" (docs/hunger-games.md#data-model).
-- Where docs/hunger-games.md's own ER sketch draws a foreign key as `bigint discord_user_id`, this
-- file uses `varchar(32) discord_id` instead - the schema's actual key has been that since V1, and
-- V4 already made the same correction for `player_playtime` rather than repeat the sketch's type.


-- One hunger games event. There is ordinarily exactly one of these open at a time, but the row
-- exists to be created more than once across a season: a rehearsal (docs/hunger-games.md#verification
-- demands one with several real clients before the event is ever called done) is itself a game, and
-- reusing team names between a rehearsal and the real event must not collide - see the per-game
-- uniqueness on hg_team below.
CREATE TABLE hg_game
(
    id      uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- REGISTRATION covers both "Registration" and "Lobby" in the lifecycle diagram - Discord keeps
    -- accepting registrations through both, so they are one state here. COUNTDOWN is the frozen
    -- spawn-tower moment, RUNNING is after release, DECIDED is the winner (or the no-winner tie)
    -- being recorded; the ceremony that follows is a display, not a further state.
    state   varchar(16) NOT NULL DEFAULT 'REGISTRATION'
        CONSTRAINT hg_game_state_check
            CHECK (state IN ('REGISTRATION', 'COUNTDOWN', 'RUNNING', 'DECIDED')),

    started timestamptz,
    ended   timestamptz,

    created timestamptz NOT NULL DEFAULT now()

    -- winner_member_id is added by an ALTER below, once hg_member exists to reference.
);

-- At most one non-DECIDED game, ever. A constant expression as the indexed value is the standard
-- way to make a partial unique index enforce "at most one row matching this WHERE", the same trick
-- payment_request_one_open_per_user_key uses per-user; here the whole table is the scope. Without
-- it, two admins racing the start command - or a rehearsal left open by accident - could produce
-- two games both accepting registrations, and Discord registration has no way to know which one a
-- team belongs to.
CREATE UNIQUE INDEX hg_game_one_open_key ON hg_game ((true))
    WHERE state <> 'DECIDED';


-- One registered team. `name` is what a player picks, not a colour - colours are generated at
-- countdown time (docs/hunger-games.md#teams-colours-and-hearts) and start out unknown.
CREATE TABLE hg_team
(
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    game_id      uuid        NOT NULL
        REFERENCES hg_game (id) ON DELETE CASCADE,

    -- 3-15 characters, enforced here as well as in the modal so a row can never violate it even if
    -- the modal's own validation is ever bypassed or loosened.
    name         varchar(15) NOT NULL
        CONSTRAINT hg_team_name_length_check CHECK (char_length(name) >= 3),

    -- NULL until the palette is generated at countdown. colour_rgb is the exact colour every
    -- plugin-rendered surface uses (nametags, HUD, chat, boards); colour_named is that colour's
    -- nearest named Minecraft colour, for the vanilla surfaces that cannot take an exact one
    -- (scoreboard team colour, tab list).
    colour_rgb   int,
    colour_named varchar(16),

    created      timestamptz NOT NULL DEFAULT now()
);

-- A team name is unique within its own game, not across every game a season ever runs - a
-- rehearsal and the real event must each be free to use "Foxes". Case-insensitive: two teams
-- named "Foxes" and "foxes" in the same game are the same collision to a player reading a lobby
-- board out loud.
CREATE UNIQUE INDEX hg_team_game_id_name_lower_key ON hg_team (game_id, lower(name));


-- One player's membership in one team, for one game.
--
-- `game_id` duplicates `hg_team.game_id` on purpose. It is what lets the partial unique index
-- below say "one active membership per player per game" without a join or a trigger - the same
-- trade-off `player_playtime`'s comments describe for denormalising a key that is only ever read
-- back, not chosen independently.
CREATE TABLE hg_member
(
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    team_id    uuid        NOT NULL
        REFERENCES hg_team (id) ON DELETE CASCADE,
    game_id    uuid        NOT NULL
        REFERENCES hg_game (id) ON DELETE CASCADE,

    discord_id varchar(32) NOT NULL
        REFERENCES discord_user (discord_id) ON DELETE CASCADE,

    -- OWNER: registered the team, always a full member from creation.
    -- INVITED: a partner was asked and has not answered yet.
    -- ACCEPTED: a partner said yes - together with OWNER, this is who counts as "on the team" for
    --           the duo/solo heart rule and the countdown-time presence check.
    -- DECLINED: a partner said no. The row is kept for history rather than deleted, and does not
    --           block a fresh invite to somebody else - see the partial index below.
    state      varchar(16) NOT NULL DEFAULT 'OWNER'
        CONSTRAINT hg_member_state_check
            CHECK (state IN ('OWNER', 'INVITED', 'ACCEPTED', 'DECLINED')),

    -- Written by the plugin's lobby broadcast ("I have read the rules and I am ready"), never by
    -- the bot - docs/hunger-games.md places readiness entirely in the Paper lobby, and nothing in
    -- the Discord half of this feature reads or writes this column.
    ready      boolean     NOT NULL DEFAULT false,

    created    timestamptz NOT NULL DEFAULT now()
);

-- One active (non-declined) membership per player per game: a Discord account cannot own or be
-- invited into two teams at once, and a repeated invite attempt while one is already pending or
-- accepted is refused by this constraint rather than by application code remembering to check.
-- A DECLINED row does not count, which is what lets a new invitation follow a declined one.
CREATE UNIQUE INDEX hg_member_one_active_membership_key ON hg_member (game_id, discord_id)
    WHERE state IN ('OWNER', 'INVITED', 'ACCEPTED');

-- The lobby roster and the "is this team complete" check both filter by team and state.
CREATE INDEX hg_member_team_id_idx ON hg_member (team_id);


-- The winner, once decided. Added after hg_member exists to reference - a forward reference across
-- two CREATE TABLEs in the same file has to be an ALTER, not a column in the first table.
ALTER TABLE hg_game
    ADD COLUMN winner_member_id uuid REFERENCES hg_member (id) ON DELETE SET NULL;


-- Everything that happens during a run: kills, deaths, disconnects, border shrinks, loot refills,
-- the tie call. Not CHECK-constrained on `type`, the same reasoning as `audit_log.action` and
-- `managed_message.kind` - a new event type must not need a migration.
CREATE TABLE hg_event
(
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    game_id   uuid        NOT NULL
        REFERENCES hg_game (id) ON DELETE CASCADE,

    -- KILL, DEATH, DISCONNECT, RECONNECT, BORDER_SHRINK, LOOT_REFILL, GAME_START, GAME_END, TIE, ...
    type      varchar(32) NOT NULL,

    -- Who did it and who it happened to, where either makes sense - a KILL has both, a
    -- BORDER_SHRINK has neither, a DEATH by the border has only a victim. ON DELETE SET NULL rather
    -- than CASCADE: an event is history and must survive a member row being reworked, even though
    -- nothing today deletes an hg_member.
    actor_id  uuid
        REFERENCES hg_member (id) ON DELETE SET NULL,
    victim_id uuid
        REFERENCES hg_member (id) ON DELETE SET NULL,

    detail    text,

    at        timestamptz NOT NULL DEFAULT now()
);

-- The kill tiebreaker (docs/hunger-games.md#winning) counts KILL events by actor_id for one game;
-- the evaluation board reads the whole log for one game in order. One index serves both.
CREATE INDEX hg_event_game_id_at_idx ON hg_event (game_id, at);
CREATE INDEX hg_event_type_actor_id_idx ON hg_event (type, actor_id);
