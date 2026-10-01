-- The bot names its status channels from the network snapshot, the query the proxy's MOTD reads as
-- well, and that query reads the active milestone and its objectives. V1 granted the proxy both
-- tables and left the bot out.
GRANT SELECT ON smp_milestone, smp_objective TO ${role_discord_bot};
