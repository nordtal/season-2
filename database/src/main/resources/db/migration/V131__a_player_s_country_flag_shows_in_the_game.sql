-- The bot writes the pack name of the country's flag glyph beside the country, so the game draws it without the list.
ALTER TABLE discord_user ADD COLUMN flag varchar(64);
