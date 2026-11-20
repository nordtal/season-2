-- Uploaded portrait pictures are immutable. An NPC names one, which its portrait shows in place of its skin's bust.
CREATE TABLE npc_artwork
(
    id         uuid        CONSTRAINT npc_artwork_pkey PRIMARY KEY,
    png        bytea       NOT NULL CONSTRAINT npc_artwork_png_check CHECK (length(png) > 0),
    actor_kind varchar(16) NOT NULL
        CONSTRAINT npc_artwork_actor_kind_check CHECK (actor_kind IN ('PERSON', 'STEWARD', 'HOST')),
    actor_id   varchar(32),
    created    timestamptz NOT NULL,
    CONSTRAINT npc_artwork_actor_id_iff_person CHECK ((actor_kind = 'PERSON') = (actor_id IS NOT NULL))
);
COMMENT ON TABLE npc_artwork IS 'Owned by steward: one immutable square PNG per upload, referenced by any number of NPCs.';

ALTER TABLE npc
    ADD COLUMN plate_style varchar(8) NOT NULL DEFAULT 'PILL'
        CONSTRAINT npc_plate_style_check CHECK (plate_style IN ('PILL', 'DARK', 'RIBBON', 'BADGE')),
    ADD COLUMN plate_colour varchar(8) NOT NULL DEFAULT 'BLUE'
        CONSTRAINT npc_plate_colour_check
            CHECK (plate_colour IN ('BLUE', 'RED', 'AMBER', 'GREEN', 'TEAL', 'VIOLET', 'ROSE', 'SLATE')),
    ADD COLUMN plate_icon varchar(8)
        CONSTRAINT npc_plate_icon_check
            CHECK (plate_icon IN ('STAR', 'PIN', 'RAIL', 'MEDAL', 'FLAG', 'WHEEL', 'SWORDS', 'SHIELD', 'HAND_IN',
                                  'CHEST', 'EYE', 'PICKAXE', 'BOX', 'HEART')),
    ADD COLUMN artwork uuid CONSTRAINT npc_artwork_fkey REFERENCES npc_artwork (id);
COMMENT ON COLUMN npc.plate_style IS 'How the name plate above its head is drawn: PILL, DARK, RIBBON or BADGE.';
COMMENT ON COLUMN npc.plate_colour IS 'The colour of its name plate and of its dialogue''s name tab.';
COMMENT ON COLUMN npc.plate_icon IS 'The symbol before its name on the plate and the tab, null for none.';
COMMENT ON COLUMN npc.artwork IS 'The uploaded picture its portrait shows, null for the bust of its skin.';

GRANT SELECT ON npc_artwork TO ${role_read};
GRANT SELECT, INSERT ON npc_artwork TO ${role_steward_ui};
