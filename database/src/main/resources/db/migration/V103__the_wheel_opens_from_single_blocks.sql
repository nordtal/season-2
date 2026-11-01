-- The wheel opens from single blocks. An admin's wheel regions are copied to wheel-blocks, each region as its centre
-- block; a region without a world matched no block and is left out. The old rows stay until every server reads the
-- new ones.
INSERT INTO setting_override (service, name, path, actor_kind, actor_id, changed)
SELECT service, name, 'wheel-blocks', actor_kind, actor_id, changed
FROM setting_override
WHERE service = 'smp'
  AND name = 'wheel'
  AND path = 'wheel-regions'
  AND jsonb_typeof(setting_override_value(service, name, path)) = 'array'
ON CONFLICT DO NOTHING;

INSERT INTO setting_override_node (service, name, path, node, parent, field, ordinal, kind, string, number, flag)
SELECT o.service, o.name, 'wheel-blocks', n.*
FROM setting_override AS o
CROSS JOIN LATERAL (SELECT setting_override_value(o.service, o.name, o.path) AS regions) AS stored
CROSS JOIN LATERAL (
    SELECT coalesce(jsonb_agg(jsonb_build_object(
               'world', region -> 'world',
               'x', floor((coalesce((region ->> 'min-x')::numeric, 0)
                   + coalesce((region ->> 'max-x')::numeric, 0)) / 2)::int,
               'y', floor((coalesce((region ->> 'min-y')::numeric, -64)
                   + coalesce((region ->> 'max-y')::numeric, 320)) / 2)::int,
               'z', floor((coalesce((region ->> 'min-z')::numeric, 0)
                   + coalesce((region ->> 'max-z')::numeric, 0)) / 2)::int)
               ORDER BY place), '[]') AS blocks
    FROM jsonb_array_elements(CASE jsonb_typeof(stored.regions) WHEN 'array' THEN stored.regions ELSE '[]' END)
        WITH ORDINALITY AS entry (region, place)
    WHERE jsonb_typeof(region -> 'world') = 'string'
      AND region ->> 'world' <> ''
) AS converted
CROSS JOIN LATERAL setting_override_nodes(converted.blocks) AS n
WHERE o.service = 'smp'
  AND o.name = 'wheel'
  AND o.path = 'wheel-regions'
  AND jsonb_typeof(stored.regions) = 'array'
ON CONFLICT DO NOTHING;
