-- Registration is discord-bot's and named by the game it registers for; the game is the game's own.
--
-- Until now the bot opened a Hunger Games game on the first registration and hunger-games wrote colours and
-- readiness into the bot's team tables. From here on a registration names its game by key, the bot writes its
-- teams and members, and the game server writes its game, its events, the colours it hands out and who said they
-- are ready. The game moves the registration's state and nothing else of it: closed when a game starts, open
-- again when that game is aborted, ended once one is decided.
--
-- This only adds. hg_team and hg_member keep their rows and grants for the proxy's standby, which runs the
-- previous release while this runs; they are dropped a release later, with the legacy REGISTRATION state.

CREATE TABLE registration
(
    id      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    game    varchar(32) NOT NULL CONSTRAINT registration_game_check CHECK (game IN ('hunger-games')),
    state   varchar(16) NOT NULL DEFAULT 'OPEN'
        CONSTRAINT registration_state_check CHECK (state IN ('OPEN', 'CLOSED', 'ENDED')),
    created timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE registration IS 'Owned by discord-bot: one round of team registration for a game. The game moves its state.';
-- One round per game that has not ended.
CREATE UNIQUE INDEX registration_one_current_key ON registration (game) WHERE state <> 'ENDED';

CREATE TABLE team
(
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    registration_id uuid        NOT NULL
        CONSTRAINT team_registration_id_fkey REFERENCES registration (id) ON DELETE CASCADE,
    name            varchar(15) NOT NULL CONSTRAINT team_name_length_check CHECK (char_length(name) >= 3),
    created         timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE team IS 'Owned by discord-bot, where teams register.';
CREATE UNIQUE INDEX team_registration_id_name_lower_key ON team (registration_id, lower(name));

CREATE TABLE team_member
(
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id         uuid        NOT NULL CONSTRAINT team_member_team_id_fkey REFERENCES team (id) ON DELETE CASCADE,
    registration_id uuid        NOT NULL
        CONSTRAINT team_member_registration_id_fkey REFERENCES registration (id) ON DELETE CASCADE,
    discord_id      varchar(32) NOT NULL
        CONSTRAINT team_member_discord_id_fkey REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    state           varchar(16) NOT NULL DEFAULT 'OWNER'
        CONSTRAINT team_member_state_check CHECK (state IN ('OWNER', 'INVITED', 'ACCEPTED', 'DECLINED')),
    created         timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE team_member IS 'Owned by discord-bot, where players join teams.';
CREATE INDEX team_member_team_id_idx ON team_member (team_id);
-- A person is in one team per registration, counting invitations.
CREATE UNIQUE INDEX team_member_one_active_membership_key ON team_member (registration_id, discord_id)
    WHERE state IN ('OWNER', 'INVITED', 'ACCEPTED');

CREATE TABLE hg_team_colour
(
    team_id      uuid        PRIMARY KEY CONSTRAINT hg_team_colour_team_id_fkey REFERENCES team (id) ON DELETE CASCADE,
    colour_rgb   integer     NOT NULL,
    colour_named varchar(16) NOT NULL
);
COMMENT ON TABLE hg_team_colour IS 'Owned by hunger-games: the colour each team plays in.';

CREATE TABLE hg_ready
(
    member_id uuid        PRIMARY KEY CONSTRAINT hg_ready_member_id_fkey REFERENCES team_member (id) ON DELETE CASCADE,
    created   timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE hg_ready IS 'Owned by hunger-games: the members who said in the lobby that they are ready.';

-- The rows so far, ids kept: a game's id becomes its registration's, so every reference stays valid.
INSERT INTO registration (id, game, state, created)
SELECT id, 'hunger-games', CASE state WHEN 'DECIDED' THEN 'ENDED' WHEN 'REGISTRATION' THEN 'OPEN' ELSE 'CLOSED' END,
       created
FROM hg_game;
INSERT INTO team (id, registration_id, name, created)
SELECT id, game_id, name, created FROM hg_team;
INSERT INTO team_member (id, team_id, registration_id, discord_id, state, created)
SELECT id, team_id, game_id, discord_id, state, created FROM hg_member;
INSERT INTO hg_team_colour (team_id, colour_rgb, colour_named)
SELECT id, colour_rgb, colour_named FROM hg_team WHERE colour_rgb IS NOT NULL AND colour_named IS NOT NULL;
INSERT INTO hg_ready (member_id, created)
SELECT id, created FROM hg_member WHERE ready;

-- A game is the game server's from its start: it names the registration it was started from, and a restart in the
-- middle of one aborts it. Only a game under way is unique; aborted ones and the legacy REGISTRATION rows are not.
ALTER TABLE hg_game ADD COLUMN registration_id uuid
    CONSTRAINT hg_game_registration_id_fkey REFERENCES registration (id) ON DELETE CASCADE;
UPDATE hg_game SET registration_id = id;
ALTER TABLE hg_game ALTER COLUMN registration_id SET NOT NULL;
CREATE INDEX hg_game_registration_id_idx ON hg_game (registration_id);
ALTER TABLE hg_game DROP CONSTRAINT hg_game_state_check;
ALTER TABLE hg_game ADD CONSTRAINT hg_game_state_check
    CHECK (state IN ('REGISTRATION', 'COUNTDOWN', 'RUNNING', 'DECIDED', 'ABORTED'));
ALTER TABLE hg_game ALTER COLUMN state SET DEFAULT 'COUNTDOWN';
DROP INDEX hg_game_one_open_key;
CREATE UNIQUE INDEX hg_game_one_under_way_key ON hg_game ((true)) WHERE state IN ('COUNTDOWN', 'RUNNING');
COMMENT ON TABLE hg_game IS 'Owned by hunger-games, which creates a game when it starts one.';

-- The winner and the events name a member of the registration.
ALTER TABLE hg_game DROP CONSTRAINT hg_game_winner_member_id_fkey;
ALTER TABLE hg_game ADD CONSTRAINT hg_game_winner_member_id_fkey
    FOREIGN KEY (winner_member_id) REFERENCES team_member (id) ON DELETE SET NULL;
ALTER TABLE hg_event DROP CONSTRAINT hg_event_actor_id_fkey;
ALTER TABLE hg_event ADD CONSTRAINT hg_event_actor_id_fkey
    FOREIGN KEY (actor_id) REFERENCES team_member (id) ON DELETE SET NULL;
ALTER TABLE hg_event DROP CONSTRAINT hg_event_victim_id_fkey;
ALTER TABLE hg_event ADD CONSTRAINT hg_event_victim_id_fkey
    FOREIGN KEY (victim_id) REFERENCES team_member (id) ON DELETE SET NULL;

-- discord-bot writes the registration and no longer opens a game.
GRANT SELECT, INSERT, UPDATE, DELETE ON registration, team, team_member TO ${role_discord_bot};
REVOKE INSERT ON hg_game FROM ${role_discord_bot};

-- hunger-games reads the registration, moves its state, and writes its own.
GRANT SELECT ON registration, team, team_member TO ${role_hunger_games};
GRANT UPDATE (state) ON registration TO ${role_hunger_games};
GRANT SELECT, INSERT, UPDATE, DELETE ON hg_team_colour, hg_ready TO ${role_hunger_games};

-- The network snapshot the proxy and the bot draw, and steward's round.
GRANT SELECT ON registration, team, team_member, hg_team_colour, hg_ready
    TO ${role_discord_bot}, ${role_proxy}, ${role_steward_ui};
