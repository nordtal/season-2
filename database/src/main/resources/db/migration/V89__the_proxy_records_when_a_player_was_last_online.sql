-- The proxy writes a player's last time online when it deletes the player's online_player row.
CREATE TABLE player_last_seen
(
    mc_uuid uuid        PRIMARY KEY,
    seen    timestamptz NOT NULL
);
COMMENT ON TABLE player_last_seen IS 'Owned by the proxy: the last time each player was online, written when the player leaves.';

GRANT SELECT ON player_last_seen TO ${role_read};
GRANT INSERT, UPDATE ON player_last_seen TO ${role_proxy};
