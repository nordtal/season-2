-- Each service publishes its own setting groups and the network's, and changes no other service's.
-- The grants of V5 let any login write any row, so one leaked password could change every service's settings.
-- The owner, Steward's agent and the restore are superusers and pass every policy. pg_dump reads with
-- row security off, which needs the backup role to pass them too.
ALTER TABLE setting_group ENABLE ROW LEVEL SECURITY;

CREATE POLICY setting_group_read ON setting_group FOR SELECT USING (true);
CREATE POLICY setting_group_discord_bot ON setting_group FOR ALL TO ${role_discord_bot}
    USING (service IN ('discord-bot', 'network')) WITH CHECK (service IN ('discord-bot', 'network'));
CREATE POLICY setting_group_proxy ON setting_group FOR ALL TO ${role_proxy}
    USING (service IN ('proxy', 'network')) WITH CHECK (service IN ('proxy', 'network'));
CREATE POLICY setting_group_limbo ON setting_group FOR ALL TO ${role_limbo}
    USING (service IN ('limbo', 'network')) WITH CHECK (service IN ('limbo', 'network'));
CREATE POLICY setting_group_hunger_games ON setting_group FOR ALL TO ${role_hunger_games}
    USING (service IN ('hunger-games', 'network')) WITH CHECK (service IN ('hunger-games', 'network'));
CREATE POLICY setting_group_smp ON setting_group FOR ALL TO ${role_smp}
    USING (service IN ('smp', 'network')) WITH CHECK (service IN ('smp', 'network'));
CREATE POLICY setting_group_steward ON setting_group FOR ALL TO ${role_steward_ui}
    USING (service IN ('steward', 'network')) WITH CHECK (service IN ('steward', 'network'));

ALTER ROLE ${role_backup} BYPASSRLS;
