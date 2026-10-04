-- The role steward logged in as on the installations of release 0.10 holds nothing any more.
--
-- V10 gave nordtal_steward what V1 and V2 had granted nordtal_steward_ui there, and nothing has logged in as the old
-- name since. Its grants were the only thing keeping it in every dump, and a dump naming a role the cluster lacks
-- does not restore cleanly, so they go first and the role itself once no kept dump names it. On a new installation
-- the role does not exist and this changes nothing.
DO $$
DECLARE
    legacy CONSTANT name := '${role_steward_ui}_ui';
    granted record;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = legacy) THEN
        RETURN;
    END IF;
    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA public FROM %I', legacy);
    EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM %I', legacy);
    EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM %I', legacy);
    EXECUTE format('REVOKE ALL ON SCHEMA public FROM %I', legacy);
    -- Memberships either way round, each revoked as the role that granted it.
    FOR granted IN
        SELECT role.rolname AS role, member.rolname AS member, grantor.rolname AS grantor
        FROM pg_auth_members membership
        JOIN pg_roles role ON role.oid = membership.roleid
        JOIN pg_roles member ON member.oid = membership.member
        JOIN pg_roles grantor ON grantor.oid = membership.grantor
        WHERE role.rolname = legacy OR member.rolname = legacy
    LOOP
        EXECUTE format('REVOKE %I FROM %I GRANTED BY %I', granted.role, granted.member, granted.grantor);
    END LOOP;
END
$$;
