-- Only steward writes setting_override; every other service reads its settings and writes none.

REVOKE INSERT ON setting_override FROM ${role_discord_bot}, ${role_proxy}, ${role_limbo}, ${role_hunger_games}, ${role_smp};
