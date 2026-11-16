-- NPC skins are immutable uploads. An NPC names one, and a copied player skin never follows the account afterwards.
CREATE TABLE npc_skin
(
    id         uuid        CONSTRAINT npc_skin_pkey PRIMARY KEY,
    model      varchar(8)  NOT NULL CONSTRAINT npc_skin_model_check CHECK (model IN ('CLASSIC', 'SLIM')),
    png        bytea       NOT NULL CONSTRAINT npc_skin_png_check CHECK (length(png) > 0),
    actor_kind varchar(16) NOT NULL
        CONSTRAINT npc_skin_actor_kind_check CHECK (actor_kind IN ('PERSON', 'STEWARD', 'HOST')),
    actor_id   varchar(32),
    created    timestamptz NOT NULL,
    CONSTRAINT npc_skin_actor_id_iff_person CHECK ((actor_kind = 'PERSON') = (actor_id IS NOT NULL))
);
COMMENT ON TABLE npc_skin IS 'Owned by steward: one immutable 64 by 64 PNG skin per upload, referenced by any number of NPCs.';

ALTER TABLE npc_dialogue_version
    ADD COLUMN snapshot jsonb CONSTRAINT npc_dialogue_version_snapshot_check
        CHECK (snapshot IS NULL OR jsonb_typeof(snapshot) = 'object');
COMMENT ON COLUMN npc_dialogue_version.snapshot IS 'The whole NPC as published, including its immutable skin reference; null on removal and in dialogue-only versions.';

ALTER TABLE pack_build
    ADD COLUMN npcs jsonb NOT NULL DEFAULT '{}';
COMMENT ON COLUMN pack_build.npcs IS 'The NPC skins by NPC id as this build drew them, including their portrait inputs.';

ALTER TABLE npc
    ADD COLUMN skin uuid CONSTRAINT npc_skin_fkey REFERENCES npc_skin (id);
COMMENT ON COLUMN npc.skin IS 'The uploaded skin this NPC uses, null for the default mannequin skin.';

-- The Nordtal dialog menus an NPC dialogue may open. smp replaces its rows at start with the menus it registered.
CREATE TABLE npc_menu
(
    key      varchar(32)  CONSTRAINT npc_menu_pkey PRIMARY KEY
        CONSTRAINT npc_menu_key_check CHECK (key ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    hint_en  varchar(64)  NOT NULL CONSTRAINT npc_menu_hint_en_check CHECK (length(btrim(hint_en)) > 0),
    hint_de  varchar(64)  NOT NULL CONSTRAINT npc_menu_hint_de_check CHECK (length(btrim(hint_de)) > 0),
    server   varchar(32)  NOT NULL CONSTRAINT npc_menu_server_check CHECK (length(server) > 0),
    changed  timestamptz  NOT NULL
);
COMMENT ON TABLE npc_menu IS 'Owned by smp: the registered dialog menus an NPC dialogue may open, with their hints in both languages.';
INSERT INTO npc_menu (key, hint_en, hint_de, server, changed)
VALUES ('objectives', 'Objectives', 'Ziele', 'smp', now());

ALTER TABLE journal_entry DROP CONSTRAINT journal_entry_action_check;
ALTER TABLE journal_entry
    ADD CONSTRAINT journal_entry_action_check CHECK (action IN (
        'ADMIN_ROOT', 'GRANT_ADMIN', 'REVOKE_ADMIN', 'ENFORCE_PACK', 'EXEMPT_PACK', 'GRANT_ACCESS', 'REVOKE_ACCESS',
        'SET_PLAYTIME', 'LINK', 'UNLINK', 'SETTLE', 'REGISTER_KEY', 'HELD_KEY', 'RENAME_KEY', 'REMOVE_KEY',
        'ADD_AUTHENTICATOR', 'USED_AUTHENTICATOR', 'REMOVE_AUTHENTICATOR', 'FORGET_FACTORS', 'WEB_PUSH_SUBSCRIBE',
        'WEB_PUSH_UNSUBSCRIBE', 'WEB_PUSH_TEST', 'SET_ALERT_PREFERENCE', 'CONSOLE', 'SAVE_SETTINGS', 'SAVE_MESSAGES',
        'SAVE_NPC', 'REMOVE_NPC', 'ADD_PLUGIN', 'REMOVE_PLUGIN', 'REQUEST_RUN', 'CANCEL_RUN', 'DOWNLOAD_BACKUP',
        'ANNOUNCE', 'COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE', 'START_GAME', 'LINK_CODES_EXHAUSTED', 'SET_AURA',
        'SET_SPINS', 'SERVICE_DOWN', 'BACKUP_STALE', 'DISK_FULL', 'MEMORY_FULL', 'IMAGE_DRIFT', 'PACK_DEAD',
        'RUN_FAILED', 'PAYMENT_NEEDS_LOOK', 'LINK_REFUSED', 'LINK_FAILED', 'PURCHASE_FAILED', 'DM_NOT_DELIVERED',
        'ROLE_NOT_CHANGED', 'ROLE_AMBIGUOUS', 'ROLE_NOT_CREATED', 'LOCK_NOT_KEPT', 'LOCK_WITHOUT_CHANNEL',
        'REMOVE_STACK', 'OPEN_INVENTORY', 'RESET_TRACK', 'SET_LAUNCH_DATE', 'LAUNCH_NOW', 'CLOSE_SEASON',
        'SET_MAINTENANCE', 'SWITCH_MODE', 'START_CHANNEL_HIDDEN', 'LANGUAGE_NOT_KEPT', 'CHANNEL_SEEN'));

ALTER TABLE smp_take_in_item DROP CONSTRAINT smp_take_in_item_purpose_check;
ALTER TABLE smp_take_in_item
    ADD CONSTRAINT smp_take_in_item_purpose_check CHECK (purpose IN ('WHEEL_PRIZE', 'DUEL_KIT', 'NPC_ITEM'));
COMMENT ON COLUMN smp_take_in_item.purpose IS 'What the item becomes: WHEEL_PRIZE, DUEL_KIT or NPC_ITEM.';

ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_kind_check;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_kind_check
        CHECK (kind IN ('COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE', 'PREVIEW_MESSAGE', 'PREVIEW_DOCUMENT',
                        'PREVIEW_NPC', 'CHECK_SETTINGS', 'SET_AURA', 'SET_SPINS', 'STORE_MODE_STATE', 'REMOVE_STACK',
                        'OPEN_INVENTORY', 'TAKE_IN_ITEM', 'SEND_PACK', 'TAKE_POSITION'));
ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_payload_iff_json;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_payload_iff_json
        CHECK ((kind IN ('CHECK_SETTINGS', 'PREVIEW_NPC')) = (payload IS NOT NULL));

GRANT SELECT ON npc_skin, npc_menu TO ${role_read};
GRANT SELECT ON npc_skin, npc_menu TO ${role_steward_ui};
GRANT INSERT ON npc_skin TO ${role_steward_ui};
GRANT SELECT ON npc_skin TO ${role_smp};
GRANT SELECT ON pack_build TO ${role_smp};
GRANT SELECT, INSERT, UPDATE, DELETE ON npc_menu TO ${role_smp};
