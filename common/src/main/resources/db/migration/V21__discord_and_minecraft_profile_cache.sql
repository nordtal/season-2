-- Names and faces: until now the database could identify an account, never describe one.
-- steward/44. Everything added here is a cache of what was last *observed*, with its own timestamp
-- next to it - never a second identity. discord_id and mc_uuid stay the only things anything in this
-- schema is looked up by, before this migration and after it.
--
-- Nothing here is UNIQUE, on purpose: a name is not a key. Two Discord accounts sharing a nickname,
-- or two Minecraft accounts (one after the other, since Mojang lets a name be released and retaken)
-- sharing a name, must stay two ordinary rows rather than a constraint violation - a UNIQUE index on
-- any of these columns would be exactly the mistake steward/44 exists to keep out of this file.
--
-- All six new columns are nullable, and deliberately without a default: a row written before this
-- migration, and a member who has since left the guild, both read as "nothing observed" rather than
-- as an empty string pretending to be a name.

ALTER TABLE discord_user
    -- The global Discord username, without a discriminator (Discord dropped those in 2023). Not
    -- enforced as unique here - this column caches what discord-bot last saw, it does not re-derive
    -- Discord's own guarantee about it.
    ADD COLUMN discord_username         varchar(32),
    ADD COLUMN discord_username_updated timestamptz,

    -- The *guild* nickname - Discord's guild-scoped display name, not the global one. NULL once the
    -- account is not a member any more: discord-bot's GuildState clears this on every path that also
    -- writes member_state = 'LEFT' or 'BANNED', because a former member has no nickname in a guild
    -- they are not in.
    ADD COLUMN discord_display_name         varchar(32),
    ADD COLUMN discord_display_name_updated timestamptz,

    -- The *guild* avatar - a member may carry a different picture per guild, and this is the one
    -- shown in this one. Cleared alongside discord_display_name for the same reason.
    ADD COLUMN discord_avatar_url         text,
    ADD COLUMN discord_avatar_url_updated timestamptz;

ALTER TABLE account_link
    -- The Minecraft name last seen at login, written by network-control. The Minecraft head image
    -- is deliberately not a column: it is a pure function of mc_uuid and a configured head-service
    -- base URL (Crafatar today, see season-2/README.md), and caching a rendering of that pair here
    -- would go stale the moment the base URL is reconfigured - the whole point of keeping it
    -- configuration rather than a stored value.
    ADD COLUMN mc_name         varchar(16),
    ADD COLUMN mc_name_updated timestamptz;
