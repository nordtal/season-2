-- What each Minecraft server knows of the game, so Steward's pickers offer exactly what the servers can bind: every
-- server writes its registries at start, and steward-agent adds the item icons it draws from Mojang's client jar
-- when the installation agreed to fetch it.

CREATE TABLE game_catalogue
(
    -- The plugin's name, which is the service the server runs as, such as smp.
    server            varchar(32) NOT NULL CONSTRAINT game_catalogue_server_check CHECK (server ~ '^[a-z][a-z0-9-]*$'),
    minecraft_version varchar(32) NOT NULL CONSTRAINT game_catalogue_version_check CHECK (length(minecraft_version) > 0),
    -- The datapacks enabled when it was exported, by name.
    datapacks         text[]      NOT NULL,
    -- Every registry and its tags, as the server's GameCatalogue record writes them.
    catalogue         jsonb       NOT NULL,
    exported          timestamptz NOT NULL,
    CONSTRAINT game_catalogue_pkey PRIMARY KEY (server)
);
COMMENT ON TABLE game_catalogue IS 'Owned by each Paper server, written at every start: its registries with their translation keys and English text. steward reads it for the pickers; steward-agent reads its versions.';

CREATE TABLE game_assets
(
    minecraft_version varchar(32) NOT NULL,
    -- One PNG: every icon at 32 pixels, left to right and then down, `columns` to a row.
    icons             bytea       NOT NULL,
    columns           integer     NOT NULL CONSTRAINT game_assets_columns_check CHECK (columns > 0),
    -- The slot of each item's icon in the sheet, by namespaced id.
    icon_index        jsonb       NOT NULL,
    fetched           timestamptz NOT NULL,
    CONSTRAINT game_assets_pkey PRIMARY KEY (minecraft_version)
);
COMMENT ON TABLE game_assets IS 'Owned by steward-agent, drawn once per Minecraft version from Mojang''s client jar and never changed after. steward serves it to signed-in admins.';

GRANT SELECT ON game_catalogue, game_assets TO ${role_read};
GRANT INSERT, UPDATE ON game_catalogue TO ${role_limbo}, ${role_hunger_games}, ${role_smp};
