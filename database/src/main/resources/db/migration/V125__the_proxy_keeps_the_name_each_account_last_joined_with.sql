-- The name each Minecraft account last joined with, linked or not, so the journal can name it after an unlink.
CREATE TABLE player_name
(
    mc_uuid uuid        PRIMARY KEY,
    mc_name varchar(16) NOT NULL CONSTRAINT player_name_mc_name_check CHECK (length(mc_name) BETWEEN 1 AND 16),
    seen    timestamptz NOT NULL
);
COMMENT ON TABLE player_name IS 'Owned by the proxy: the name each Minecraft account last joined with, written at every login, refused ones too.';

GRANT SELECT ON player_name TO ${role_read};
GRANT INSERT, UPDATE ON player_name TO ${role_proxy};
