-- A spawn station added in Steward without taking a block kept its field defaults, nordtal 0/64/0, where no rail can
-- stand. Such an entry moves to the block of the station called Spawn in that world, when exactly one has the name.
-- That station becomes the spawn station in place, with no placer and no name of its own, so smp finds it standing
-- there and keeps its row and its favourites. An entry an admin took from the game stays as it is.
WITH placeholder AS (
    SELECT o.service, o.name, o.path, s.id AS station, s.x, s.y, s.z
    FROM setting_override o
    JOIN smp_rail_station s ON s.world = 'nordtal' AND s.name = 'Spawn'
    WHERE o.service = 'smp' AND o.name = 'rails' AND o.path = 'spawn-station'
      AND (SELECT count(*) FROM smp_rail_station WHERE world = 'nordtal' AND name = 'Spawn') = 1
      AND (SELECT count(*) FROM setting_override_node n
           WHERE (n.service, n.name, n.path) = (o.service, o.name, o.path) AND n.kind = 'OBJECT') = 1
      AND (SELECT count(*) FROM setting_override_node n
           WHERE (n.service, n.name, n.path) = (o.service, o.name, o.path) AND n.field IS NOT NULL) = 4
      AND (SELECT count(*) FROM setting_override_node n
           WHERE (n.service, n.name, n.path) = (o.service, o.name, o.path)
             AND ((n.field = 'world' AND n.string = 'nordtal') OR (n.field = 'x' AND n.number = 0)
                  OR (n.field = 'y' AND n.number = 64) OR (n.field = 'z' AND n.number = 0))) = 4
),
moved AS (
    UPDATE setting_override_node n
    SET number = CASE n.field WHEN 'x' THEN p.x WHEN 'y' THEN p.y ELSE p.z END
    FROM placeholder p
    WHERE (n.service, n.name, n.path) = (p.service, p.name, p.path) AND n.field IN ('x', 'y', 'z')
    RETURNING n.service
),
taken_over AS (
    UPDATE smp_rail_station s
    SET placed_by = NULL, name = NULL, named = NULL, renamed_unseen = false, hidden = false
    FROM placeholder p
    WHERE s.id = p.station
    RETURNING s.id
)
UPDATE setting_override o
SET actor_kind = 'HOST', actor_id = NULL, changed = now()
FROM placeholder p
WHERE (o.service, o.name, o.path) = (p.service, p.name, p.path);
