-- A player's language and time zone are their own, or else the network's: NULL means the network's default from
-- the network settings. The bot fills the language from a member's language role and leaves it NULL without one;
-- nothing fills the zone yet. Rows written before keep the language they hold.
ALTER TABLE discord_user ALTER COLUMN locale DROP NOT NULL;
ALTER TABLE discord_user ALTER COLUMN locale DROP DEFAULT;
-- An IANA zone such as Europe/Berlin.
ALTER TABLE discord_user
    ADD COLUMN time_zone varchar(64)
        CONSTRAINT discord_user_time_zone_check CHECK (time_zone ~ '^[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+)*$');

-- Every process reads a player's aura with the rest of who they are, in the one query at login.
GRANT SELECT ON smp_player TO ${role_read};
