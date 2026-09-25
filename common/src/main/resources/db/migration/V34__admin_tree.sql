-- Admin is a grant tree decided in Steward, no longer a mirror of a Discord role.
--
-- Every admin but one was made one by another admin, and `admin_granted_by` records who. The one
-- without a granter is the root: whoever completed Steward's sign-in first while nobody was an
-- admin. An admin may revoke only somebody below them in this tree, and a revocation takes the whole
-- branch under that person with it - so "who granted whom" is not history, it is the permission.
--
-- WHY THE FLAG STAYS. `admin` is what the proxy, the plugins and the bot already read on paths that
-- have to stay one query. It is kept and tied to the tree by a CHECK: a row is an admin exactly when
-- it carries a grant. That also shuts the door the old mirror would come through - a bot built
-- before this migration writes `admin = true` with no grant, and the database refuses it.
--
-- EVERYBODY STARTS AS A NON-ADMIN. The flags the role mirror wrote carry no granter and cannot be
-- placed in a tree, so they are cleared, and the next person through Steward's sign-in becomes the
-- root. Until then nobody is an admin anywhere, which is the intended way in.
UPDATE discord_user
SET admin = false
WHERE admin;

ALTER TABLE discord_user
    ADD COLUMN admin_granted_by varchar(32)
        CONSTRAINT discord_user_admin_granted_by_fkey REFERENCES discord_user (discord_id),
    ADD COLUMN admin_granted_at timestamptz,
    ADD CONSTRAINT discord_user_admin_is_granted
        CHECK (admin = (admin_granted_at IS NOT NULL)),
    ADD CONSTRAINT discord_user_admin_granter_only_for_admins
        CHECK (admin OR admin_granted_by IS NULL),
    ADD CONSTRAINT discord_user_admin_not_self_granted
        CHECK (admin_granted_by IS DISTINCT FROM discord_id);

-- One root at most. Two sign-ins racing an empty table would otherwise both become one.
CREATE UNIQUE INDEX discord_user_one_admin_root
    ON discord_user ((true))
    WHERE admin AND admin_granted_by IS NULL;

CREATE INDEX discord_user_admin_granted_by
    ON discord_user (admin_granted_by)
    WHERE admin_granted_by IS NOT NULL;

-- Every grant ever made, for the global limit of three an hour. The tree itself cannot answer
-- that: a grant revoked five minutes later has left it again, and still counted.
CREATE TABLE admin_grant
(
    id         bigserial PRIMARY KEY,
    discord_id varchar(32) NOT NULL,
    granted_by varchar(32) NOT NULL,
    granted    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX admin_grant_granted ON admin_grant (granted);

-- The flags just cleared have to leave every connected session too, not only the next login.
SELECT pg_notify('nordtal_admin', '');
