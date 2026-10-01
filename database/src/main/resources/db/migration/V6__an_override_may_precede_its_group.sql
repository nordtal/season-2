-- An update run sets the pack the proxy sends before that proxy first starts and publishes its groups, so an
-- override no longer needs its group's row. Steward's change takes an advisory lock per group instead.
ALTER TABLE setting_override DROP CONSTRAINT setting_override_group_fkey;
COMMENT ON TABLE setting_override IS 'Owned by steward, where an admin changes a setting and an update run sets the pack the proxy sends: one row per value that differs from its default. Every process reads its own rows and those of network.';
