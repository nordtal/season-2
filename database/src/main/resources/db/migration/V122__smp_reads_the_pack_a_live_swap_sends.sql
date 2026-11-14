-- smp reads the pack of each SEND_PACK row it claims, as it reads the rows of its other kinds.
--
-- This only adds.

GRANT SELECT ON smp_send_pack TO ${role_read};
