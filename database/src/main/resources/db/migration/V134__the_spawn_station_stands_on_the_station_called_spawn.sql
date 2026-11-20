-- A spawn station added in Steward without taking a block kept its field defaults, nordtal 0/64/0, where no rail can
-- stand. Such an entry moves to the block of the one station called Spawn in that world, which smp then takes over.
-- An entry an admin took from the game stays as it is.
WITH placeholder AS (
    SELECT o.service, o.name, o.path, s.x, s.y, s.z
    FROM setting_override o
    JOIN smp_rail_station s ON s.world = 'nordtal' AND s.name = 'Spawn'
    WHERE o.service = 'smp' AND o.name = 'rails' AND o.path = 'spawn-station'
      AND (SELECT count(*) FROM smp_rail_station WHERE world = 'nordtal' AND name = 'Spawn') = 1
      AND (SELECT count(*) FROM setting_override_node n
           WHERE (n.service, n.name, n.path) = (o.service, o.name, o.path) AND n.kind = 'OBJECT') = 1
      AND (SELECT string_agg(n.field || '=' || coalesce(n.string, n.number::text), ' ' ORDER BY n.field)
           FROM setting_override_node n
           WHERE (n.service, n.name, n.path) = (o.service, o.name, o.path) AND n.field IS NOT NULL)
          = 'world=nordtal x=0 y=64 z=0'
),
moved AS (
    UPDATE setting_override_node n
    SET number = CASE n.field WHEN 'x' THEN p.x WHEN 'y' THEN p.y ELSE p.z END
    FROM placeholder p
    WHERE (n.service, n.name, n.path) = (p.service, p.name, p.path) AND n.field IN ('x', 'y', 'z')
    RETURNING n.service
)
UPDATE setting_override o
SET actor_kind = 'HOST', actor_id = NULL, changed = now()
FROM placeholder p
WHERE (o.service, o.name, o.path) = (p.service, p.name, p.path);
