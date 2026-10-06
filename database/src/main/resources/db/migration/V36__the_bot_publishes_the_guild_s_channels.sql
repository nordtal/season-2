-- The guild's channels as discord-bot sees them, so Steward's pickers name a channel without holding the bot's token.
--
-- The bot writes the whole list at start and whenever a channel changes; steward only reads it, and shows the last
-- list while the bot is down. This only adds.

CREATE TABLE guild_channels
(
    guild_id  varchar(32) NOT NULL CONSTRAINT guild_channels_guild_id_check CHECK (guild_id ~ '^[0-9]{1,20}$'),
    -- Every channel in the guild's own order, categories included, as the GuildChannel record writes them.
    channels  jsonb       NOT NULL CONSTRAINT guild_channels_channels_check CHECK (jsonb_typeof(channels) = 'array'),
    published timestamptz NOT NULL,
    CONSTRAINT guild_channels_pkey PRIMARY KEY (guild_id)
);
COMMENT ON TABLE guild_channels IS 'Owned by discord-bot, written at start and whenever a channel of its guild changes. steward reads it for the channel pickers.';

GRANT SELECT ON guild_channels TO ${role_steward_ui};
-- The upsert's conflict target is read, so the bot may see the guild of the row and not read its list back.
GRANT SELECT (guild_id), INSERT, UPDATE ON guild_channels TO ${role_discord_bot};
