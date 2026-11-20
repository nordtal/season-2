-- Each pack build records which uploaded portrait picture it drew for each NPC, as npcs records the skins.
ALTER TABLE pack_build
    ADD COLUMN artworks jsonb NOT NULL DEFAULT '{}'::jsonb;
COMMENT ON COLUMN pack_build.artworks IS 'The artwork id the build drew as each NPC''s portrait, by NPC id.';
