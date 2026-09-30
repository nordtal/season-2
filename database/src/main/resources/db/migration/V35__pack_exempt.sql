-- One player at a time may be let through without the resource pack, by an admin in Steward.
--
-- It exists for whoever draws the pack: a server's pack sits on top of every local one, so their
-- own work is invisible on the network until the network stops sending its pack to them.
--
-- The default is the pack. A row is exempt only while both columns are set, and nothing but an
-- admin's click sets them; there is no expiry, the exemption stays until an admin takes it back.
-- `pack_exempt_by` names who set it, as `admin_granted_by` does for admins.
ALTER TABLE discord_user
    ADD COLUMN pack_exempt_by varchar(32)
        CONSTRAINT discord_user_pack_exempt_by_fkey REFERENCES discord_user (discord_id),
    ADD COLUMN pack_exempt_at timestamptz,
    ADD CONSTRAINT discord_user_pack_exempt_is_set_by_someone
        CHECK ((pack_exempt_by IS NULL) = (pack_exempt_at IS NULL));
