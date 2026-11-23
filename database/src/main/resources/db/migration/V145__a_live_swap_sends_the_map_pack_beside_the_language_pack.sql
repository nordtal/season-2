-- A live swap sends the map pack beside the language pack, because sending one pack drops the other on the client.
--
-- A row from before the map pack has neither value. This only adds.

ALTER TABLE smp_send_pack
    -- Where the players' clients download the map pack's zip, or null without a map pack.
    ADD COLUMN map_url  text,
    -- The SHA-1 of the map pack's zip in lower-case hex, or null without a map pack.
    ADD COLUMN map_sha1 char(40) CONSTRAINT smp_send_pack_map_sha1_hex CHECK (map_sha1 ~ '^[0-9a-f]{40}$'),
    ADD CONSTRAINT smp_send_pack_map_whole CHECK ((map_url IS NULL) = (map_sha1 IS NULL));
