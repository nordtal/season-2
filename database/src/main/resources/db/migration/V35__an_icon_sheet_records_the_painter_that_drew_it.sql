-- A sheet records which drawing code painted it, so steward-agent draws it again when that code changes.
--
-- The sheets before this have no painter: the empty default never equals one, so each is drawn again once.

ALTER TABLE game_assets
    ADD COLUMN painter varchar(64) NOT NULL DEFAULT '';
COMMENT ON COLUMN game_assets.painter IS 'The identity of the drawing code that painted the sheet, as steward-agent computes it; empty for a sheet drawn before painters were recorded.';
COMMENT ON TABLE game_assets IS 'Owned by steward-agent, drawn once per Minecraft version from Mojang''s client jar and drawn again, replacing the row, when the painter that drew it is not the running one. steward serves it to signed-in admins.';
