-- Each Paper server keeps two states per player, Survival (which Adventure shares) and Creative, so items never cross
-- between the two modes. The state the player is in lives in the server's own player data; the other one is here.

CREATE TABLE game_mode_state
(
    -- The plugin's name, which is the service the server runs as, such as smp.
    server      varchar(32) NOT NULL CONSTRAINT game_mode_state_server_check CHECK (server ~ '^[a-z][a-z0-9-]*$'),
    player_uuid uuid        NOT NULL,
    mode        varchar(16) NOT NULL CONSTRAINT game_mode_state_mode_check CHECK (mode IN ('SURVIVAL', 'CREATIVE')),
    level       integer     NOT NULL CONSTRAINT game_mode_state_level_check CHECK (level >= 0),
    -- The progress towards the next level, from 0 to 1.
    experience  real        NOT NULL CONSTRAINT game_mode_state_experience_check CHECK (experience BETWEEN 0 AND 1),
    saved       timestamptz NOT NULL,
    CONSTRAINT game_mode_state_pkey PRIMARY KEY (server, player_uuid, mode)
);
COMMENT ON TABLE game_mode_state IS 'Owned by each Paper server: the state of the mode a player left, written at every switch between Survival and Creative.';

CREATE TABLE game_mode_state_item
(
    server      varchar(32) NOT NULL,
    player_uuid uuid        NOT NULL,
    mode        varchar(16) NOT NULL,
    -- INVENTORY holds the main inventory, the armour and the offhand in Paper's slot order.
    container   varchar(16) NOT NULL
        CONSTRAINT game_mode_state_item_container_check CHECK (container IN ('INVENTORY', 'ENDER_CHEST')),
    slot        smallint    NOT NULL CONSTRAINT game_mode_state_item_slot_check CHECK (slot >= 0),
    -- The namespaced item id, such as minecraft:oak_log.
    item        varchar(64) NOT NULL CONSTRAINT game_mode_state_item_item_check CHECK (item ~ '^[a-z0-9_.-]+:[a-z0-9_./-]+$'),
    amount      integer     NOT NULL CONSTRAINT game_mode_state_item_amount_check CHECK (amount > 0),
    -- The whole stack as Paper's ItemStack#serializeAsBytes writes it, which upgrades it to a later Minecraft version.
    data        bytea       NOT NULL,
    CONSTRAINT game_mode_state_item_pkey PRIMARY KEY (server, player_uuid, mode, container, slot),
    CONSTRAINT game_mode_state_item_state_fkey FOREIGN KEY (server, player_uuid, mode)
        REFERENCES game_mode_state (server, player_uuid, mode) ON DELETE CASCADE
);
COMMENT ON TABLE game_mode_state_item IS 'Owned by each Paper server: one stack of a stored mode state per slot.';

GRANT SELECT, INSERT, UPDATE, DELETE ON game_mode_state, game_mode_state_item
    TO ${role_limbo}, ${role_hunger_games}, ${role_smp};
