-- Registration has one shape: the bot's registration, team and team_member tables.
--
-- The legacy team tables go, and so does a game in state REGISTRATION, the legacy shape's open round. Its
-- registration row stays, so nothing that names it loses its target.

DROP TABLE hg_member;
DROP TABLE hg_team;

DELETE FROM hg_game WHERE state = 'REGISTRATION';
ALTER TABLE hg_game DROP CONSTRAINT hg_game_state_check;
ALTER TABLE hg_game ADD CONSTRAINT hg_game_state_check CHECK (state IN ('COUNTDOWN', 'RUNNING', 'DECIDED', 'ABORTED'));
