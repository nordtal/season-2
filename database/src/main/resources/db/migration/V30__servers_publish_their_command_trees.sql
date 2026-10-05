-- Every server's command tree as its own Brigadier dispatcher holds it, so Steward's console suggests what that server
-- accepts: the game's commands, every plugin's and ours, each argument by its name.
--
-- Each server writes its own row at start and again when its commands change; steward only reads. This only adds.

CREATE TABLE command_tree
(
    -- The service the server runs as, such as smp or proxy.
    server    varchar(32) NOT NULL CONSTRAINT command_tree_server_check CHECK (server ~ '^[a-z][a-z0-9-]*$'),
    -- Every node by its index, the root first, as the CommandTree record writes them.
    tree      jsonb       NOT NULL,
    published timestamptz NOT NULL,
    CONSTRAINT command_tree_pkey PRIMARY KEY (server)
);
COMMENT ON TABLE command_tree IS 'Owned by each Minecraft server and the proxy, written at start and whenever its commands change. steward reads it for the console''s suggestions.';

GRANT SELECT ON command_tree TO ${role_steward_ui};
-- The upsert's conflict target is read, so a server may see the names of the rows and nothing of their trees.
GRANT SELECT (server), INSERT, UPDATE ON command_tree TO ${role_proxy}, ${role_limbo}, ${role_hunger_games}, ${role_smp};
