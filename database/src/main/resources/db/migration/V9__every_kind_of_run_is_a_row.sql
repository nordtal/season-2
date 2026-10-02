-- One run model carries out every kind: a restore, a recreate, a deploy and a plugin removal are rows like an update.
ALTER TABLE steward_inbox DROP CONSTRAINT steward_inbox_kind_check;
ALTER TABLE steward_inbox ADD CONSTRAINT steward_inbox_kind_check
    CHECK (kind IN ('UPDATE', 'RESTART', 'BACKUP', 'DOWN', 'START', 'RESTORE', 'RECREATE', 'DEPLOY', 'REMOVE_PLUGIN'));
