-- Prism, the SMP's block logger, creates and migrates its own tables and functions here as smp's role.
-- Flyway manages public alone, so nothing here is one of its migrations.
CREATE SCHEMA prism AUTHORIZATION ${role_smp};
COMMENT ON SCHEMA prism IS 'Owned by smp: Prism''s log of block changes and its rollbacks, which Prism creates and migrates itself.';
