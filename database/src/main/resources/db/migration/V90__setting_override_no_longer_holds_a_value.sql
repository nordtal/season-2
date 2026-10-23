-- setting_override_node holds an admin's setting value, and no process reads the composite column.
ALTER TABLE setting_override DROP COLUMN value;
