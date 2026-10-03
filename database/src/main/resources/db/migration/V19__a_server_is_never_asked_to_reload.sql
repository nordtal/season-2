-- Settings and message overrides reach every process on the signal hub, so no server is asked to reload any more.
-- The rows already answered are history of a kind that no longer exists, and would fail the narrowed checks.
DELETE FROM smp_inbox WHERE kind = 'RELOAD';
ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_kind_check;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_kind_check CHECK (kind IN ('COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE'));
COMMENT ON TABLE smp_inbox IS 'Owned by smp, which claims and carries out every row. steward asks for a track action.';

DELETE FROM hunger_games_inbox WHERE kind = 'RELOAD';
ALTER TABLE hunger_games_inbox DROP CONSTRAINT hunger_games_inbox_kind_check;
ALTER TABLE hunger_games_inbox ADD CONSTRAINT hunger_games_inbox_kind_check CHECK (kind IN ('START_GAME'));
COMMENT ON TABLE hunger_games_inbox IS 'Owned by hunger-games, which claims and carries out every row. steward asks for the start.';

-- Reload was all the waiting room and the proxy were ever asked, so their inboxes go with their grants.
DROP TABLE limbo_inbox;
DROP TABLE proxy_inbox;

-- The server list text is a message of the proxy's bundle now, not a settings group of the network.
DELETE FROM setting_override WHERE service = 'network' AND name = 'motd';
DELETE FROM setting_group WHERE service = 'network' AND name = 'motd';
