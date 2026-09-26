-- A sixth kind on the bot's inbox: re-read the message bundles.
--
-- WHY IT BELONGS HERE AND NOT ON A RELOAD TABLE OF ITS OWN. `ConfigApi` already knows how to reload
-- a Minecraft service - a map from a file to a console command, sent through the container's tmux
-- console. The bot has no console; it is not a Minecraft server. It does have an inbox, as of V32,
-- and "do this thing that only you can do" is exactly what that inbox is. A second mechanism for
-- one service would be a second place to look for why a saved change did nothing.
--
-- The subject is the bundle whose override was just written. The bot re-reads all of its bundles
-- either way - they are layered, and re-reading one of them is not a thing the loader can do - so
-- this is recorded rather than acted on. It is still worth recording: "who caused this" is the
-- question an audit trail exists to answer.
ALTER TABLE access_request
    DROP CONSTRAINT access_request_kind_check;

ALTER TABLE access_request
    ADD CONSTRAINT access_request_kind_check
        CHECK (kind IN ('GRANT', 'REVOKE', 'SETTLE', 'UNLINK', 'SET_PLAYTIME', 'RELOAD_MESSAGES'));
