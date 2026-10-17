-- The role steward logged in as on the installations of release 0.10 goes, now that no kept dump names it.
--
-- V27 took every grant and membership from it, so no dump taken since names it, and the older ones have left the
-- backup retention. On a new installation the role does not exist and this changes nothing.
DO $$
BEGIN
    EXECUTE format('DROP ROLE IF EXISTS %I', '${role_steward_ui}_ui');
END
$$;
