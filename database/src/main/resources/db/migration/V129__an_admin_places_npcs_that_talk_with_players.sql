-- An NPC is a figure an admin places in a world, with a name in both languages, a pose, the pitch of its voice, the
-- items it wears and holds, and a dialogue. smp spawns every row and re-reads them on nordtal_npc.
CREATE TABLE npc
(
    id         uuid             CONSTRAINT npc_pkey PRIMARY KEY,
    name_en    varchar(32)      NOT NULL CONSTRAINT npc_name_en_check CHECK (length(btrim(name_en)) > 0),
    name_de    varchar(32)      NOT NULL CONSTRAINT npc_name_de_check CHECK (length(btrim(name_de)) > 0),
    world      varchar(64)      NOT NULL CONSTRAINT npc_world_check CHECK (length(world) > 0),
    x          double precision NOT NULL,
    y          double precision NOT NULL,
    z          double precision NOT NULL,
    -- Degrees, as Minecraft counts them: 0 faces south, -90 east.
    yaw        real             NOT NULL CONSTRAINT npc_yaw_check CHECK (yaw >= -180 AND yaw <= 180),
    pose       varchar(8)       NOT NULL CONSTRAINT npc_pose_check CHECK (pose IN ('STAND', 'SIT', 'CROUCH')),
    -- The pitch its typing sound plays at.
    pitch      real             NOT NULL CONSTRAINT npc_pitch_check CHECK (pitch >= 0.5 AND pitch <= 2),
    -- The steps of its dialogue, read and written whole and never queried by part.
    dialogue   jsonb            NOT NULL CONSTRAINT npc_dialogue_check CHECK (jsonb_typeof(dialogue) = 'object'),
    actor_kind varchar(16)      NOT NULL
        CONSTRAINT npc_actor_kind_check CHECK (actor_kind IN ('PERSON', 'STEWARD', 'HOST')),
    actor_id   varchar(32),
    changed    timestamptz      NOT NULL,
    CONSTRAINT npc_actor_id_iff_person CHECK ((actor_kind = 'PERSON') = (actor_id IS NOT NULL))
);
COMMENT ON TABLE npc IS 'Owned by steward, where an admin places and writes NPCs: one row per NPC. smp spawns them.';

-- What an NPC wears and holds, each taken in from the game as a whole stack.
CREATE TABLE npc_equipment
(
    npc  uuid         NOT NULL CONSTRAINT npc_equipment_npc_fkey REFERENCES npc (id) ON DELETE CASCADE,
    slot varchar(8)   NOT NULL
        CONSTRAINT npc_equipment_slot_check CHECK (slot IN ('HEAD', 'CHEST', 'LEGS', 'FEET', 'HAND', 'OFF_HAND')),
    -- The item's key, such as minecraft:iron_sword.
    item varchar(128) NOT NULL CONSTRAINT npc_equipment_item_check CHECK (length(item) > 0),
    -- The whole stack as Base64 of its serialised bytes.
    data text         NOT NULL CONSTRAINT npc_equipment_data_check CHECK (length(data) > 0),
    CONSTRAINT npc_equipment_pkey PRIMARY KEY (npc, slot)
);
COMMENT ON TABLE npc_equipment IS 'Owned by steward: the items an NPC wears and holds, one row per slot.';

-- Every save of an NPC's dialogue is kept whole, so Steward can restore it.
CREATE TABLE npc_dialogue_version
(
    id         bigserial   CONSTRAINT npc_dialogue_version_pkey PRIMARY KEY,
    npc        uuid        NOT NULL CONSTRAINT npc_dialogue_version_npc_fkey REFERENCES npc (id) ON DELETE CASCADE,
    dialogue   jsonb       NOT NULL
        CONSTRAINT npc_dialogue_version_dialogue_check CHECK (jsonb_typeof(dialogue) = 'object'),
    actor_kind varchar(16) NOT NULL
        CONSTRAINT npc_dialogue_version_actor_kind_check CHECK (actor_kind IN ('PERSON', 'STEWARD', 'HOST')),
    actor_id   varchar(32),
    saved      timestamptz NOT NULL,
    CONSTRAINT npc_dialogue_version_actor_id_iff_person CHECK ((actor_kind = 'PERSON') = (actor_id IS NOT NULL))
);
CREATE INDEX npc_dialogue_version_npc_idx ON npc_dialogue_version (npc, id);
COMMENT ON TABLE npc_dialogue_version IS 'Owned by steward: one row per save of an NPC''s dialogue, never changed. Only steward reads it.';

-- A player who ticked "Don't show again" at an NPC's hand-off into a menu. A right-click then opens that menu at once.
CREATE TABLE npc_skip
(
    npc    uuid        NOT NULL CONSTRAINT npc_skip_npc_fkey REFERENCES npc (id) ON DELETE CASCADE,
    -- The Minecraft account of the player.
    player uuid        NOT NULL,
    -- The menu the dialogue handed the player to.
    menu   varchar(32) NOT NULL CONSTRAINT npc_skip_menu_check CHECK (menu ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    CONSTRAINT npc_skip_pkey PRIMARY KEY (npc, player)
);
COMMENT ON TABLE npc_skip IS 'Owned by smp: a player who skips an NPC''s dialogue and goes straight to its menu.';

-- The spawn NPC starts where smp's settings put it until now, with a dialogue that hands the player to the objectives.
INSERT INTO npc (id, name_en, name_de, world, x, y, z, yaw, pose, pitch, dialogue, actor_kind, actor_id, changed)
VALUES ('6f1c3c5e-6a43-4c1b-9a7e-2a5d8c0b3e41', 'Runa', 'Runa', 'nordtal', 3980, 84, 2194, -180, 'STAND', 1,
        '{"start": "welcome", "steps": [{"id": "welcome", "x": 0, "y": 0, "text": {"en": "Welcome to Nordtal! Together we reach the next milestone. Have a look at what is wanted.", "de": "Willkommen im Nordtal! Gemeinsam erreichen wir den nächsten Meilenstein. Schau dir an, was gebraucht wird."}, "answers": [], "menu": "objectives"}]}',
        'HOST', NULL, now());
INSERT INTO npc_dialogue_version (npc, dialogue, actor_kind, actor_id, saved)
SELECT id, dialogue, actor_kind, actor_id, changed
FROM npc;

GRANT SELECT ON npc, npc_equipment, npc_skip TO ${role_read};
GRANT INSERT, UPDATE, DELETE ON npc, npc_equipment TO ${role_steward_ui};
GRANT SELECT, INSERT ON npc_dialogue_version TO ${role_steward_ui};
GRANT USAGE ON SEQUENCE npc_dialogue_version_id_seq TO ${role_steward_ui};
GRANT INSERT, UPDATE, DELETE ON npc_skip TO ${role_smp};
