-- Spawn protected areas and duel arena / negotiation areas are stored as 3D polygon lists.
-- Any legacy cuboid overrides (including axes stored with min > max) are converted in place and their old rows removed.
DO $$
DECLARE
    area_spec   record;
    meta        record;
    world_name  text;
    min_x       int;
    max_x       int;
    min_y       int;
    max_y       int;
    min_z       int;
    max_z       int;
    x1          int;
    x2          int;
    y1          int;
    y2          int;
    z1          int;
    z2          int;
    converted   jsonb;
    spawn_areas jsonb;
BEGIN
    FOR area_spec IN
        SELECT *
        FROM (VALUES
                  ('arena', 'Arena', 115, 131, 67, 76, 80, 96),
                  ('negotiation-area', 'Negotiation', 99, 101, 68, 69, 87, 89))
                 AS spec (prefix, label, def_min_x, def_max_x, def_min_y, def_max_y, def_min_z, def_max_z)
    LOOP
        IF EXISTS (SELECT 1
                   FROM setting_override
                   WHERE service = 'smp'
                     AND name = 'duels'
                     AND path LIKE area_spec.prefix || '.%') THEN
            IF NOT EXISTS (SELECT 1
                           FROM setting_override
                           WHERE service = 'smp'
                             AND name = 'duels'
                             AND path = area_spec.prefix) THEN
                SELECT actor_kind, actor_id, changed
                INTO meta
                FROM setting_override
                WHERE service = 'smp'
                  AND name = 'duels'
                  AND path LIKE area_spec.prefix || '.%'
                ORDER BY changed DESC
                LIMIT 1;

                SELECT coalesce(
                               (SELECT string
                                FROM setting_override_node
                                WHERE service = 'smp'
                                  AND name = 'duels'
                                  AND path = area_spec.prefix || '.world'
                                  AND node = 0
                                  AND kind = 'STRING'),
                               'nordtal')
                INTO world_name;

                SELECT coalesce(
                               (SELECT number::int
                                FROM setting_override_node
                                WHERE service = 'smp'
                                  AND name = 'duels'
                                  AND path = area_spec.prefix || '.min-x'
                                  AND node = 0
                                  AND kind = 'NUMBER'),
                               area_spec.def_min_x),
                       coalesce(
                               (SELECT number::int
                                FROM setting_override_node
                                WHERE service = 'smp'
                                  AND name = 'duels'
                                  AND path = area_spec.prefix || '.max-x'
                                  AND node = 0
                                  AND kind = 'NUMBER'),
                               area_spec.def_max_x),
                       coalesce(
                               (SELECT number::int
                                FROM setting_override_node
                                WHERE service = 'smp'
                                  AND name = 'duels'
                                  AND path = area_spec.prefix || '.min-y'
                                  AND node = 0
                                  AND kind = 'NUMBER'),
                               area_spec.def_min_y),
                       coalesce(
                               (SELECT number::int
                                FROM setting_override_node
                                WHERE service = 'smp'
                                  AND name = 'duels'
                                  AND path = area_spec.prefix || '.max-y'
                                  AND node = 0
                                  AND kind = 'NUMBER'),
                               area_spec.def_max_y),
                       coalesce(
                               (SELECT number::int
                                FROM setting_override_node
                                WHERE service = 'smp'
                                  AND name = 'duels'
                                  AND path = area_spec.prefix || '.min-z'
                                  AND node = 0
                                  AND kind = 'NUMBER'),
                               area_spec.def_min_z),
                       coalesce(
                               (SELECT number::int
                                FROM setting_override_node
                                WHERE service = 'smp'
                                  AND name = 'duels'
                                  AND path = area_spec.prefix || '.max-z'
                                  AND node = 0
                                  AND kind = 'NUMBER'),
                               area_spec.def_max_z)
                INTO min_x, max_x, min_y, max_y, min_z, max_z;

                x1 := LEAST(min_x, max_x);
                x2 := GREATEST(min_x, max_x);
                y1 := LEAST(min_y, max_y);
                y2 := GREATEST(min_y, max_y);
                z1 := LEAST(min_z, max_z);
                z2 := GREATEST(min_z, max_z);

                converted := jsonb_build_array(
                        jsonb_build_object(
                                'name', area_spec.label,
                                'world', world_name,
                                'min-y', y1,
                                'max-y', y2,
                                'corners', jsonb_build_array(
                                        jsonb_build_object('x', x1, 'z', z1),
                                        jsonb_build_object('x', x2, 'z', z1),
                                        jsonb_build_object('x', x2, 'z', z2),
                                        jsonb_build_object('x', x1, 'z', z2))));

                INSERT INTO setting_override (service, name, path, actor_kind, actor_id, changed)
                VALUES ('smp', 'duels', area_spec.prefix, meta.actor_kind, meta.actor_id, meta.changed)
                ON CONFLICT DO NOTHING;

                INSERT INTO setting_override_node
                    (service, name, path, node, parent, field, ordinal, kind, string, number, flag)
                SELECT 'smp', 'duels', area_spec.prefix, n.*
                FROM setting_override_nodes(converted) AS n
                ON CONFLICT DO NOTHING;
            END IF;

            DELETE FROM setting_override
            WHERE service = 'smp'
              AND name = 'duels'
              AND path LIKE area_spec.prefix || '.%';
        END IF;
    END LOOP;

    IF EXISTS (SELECT 1
               FROM setting_override
               WHERE service = 'smp'
                 AND name = 'spawn'
                 AND path = 'protected-areas') THEN
        spawn_areas := setting_override_value('smp', 'spawn', 'protected-areas');
        IF jsonb_typeof(spawn_areas) = 'array'
            AND EXISTS (SELECT 1
                        FROM jsonb_array_elements(spawn_areas) AS entry
                        WHERE jsonb_typeof(entry) = 'object'
                          AND NOT (entry ? 'corners')) THEN
            SELECT coalesce(
                           jsonb_agg(
                                   CASE
                                       WHEN entry ? 'corners' THEN entry
                                       ELSE jsonb_build_object(
                                               'name', coalesce(nullif(entry ->> 'name', ''), 'Area ' || place),
                                               'world', coalesce(entry ->> 'world', ''),
                                               'min-y', LEAST(coalesce((entry ->> 'min-y')::int, -64),
                                                              coalesce((entry ->> 'max-y')::int, 320)),
                                               'max-y', GREATEST(coalesce((entry ->> 'min-y')::int, -64),
                                                                 coalesce((entry ->> 'max-y')::int, 320)),
                                               'corners', jsonb_build_array(
                                                       jsonb_build_object(
                                                               'x', LEAST(coalesce((entry ->> 'min-x')::int, 0),
                                                                          coalesce((entry ->> 'max-x')::int, 0)),
                                                               'z', LEAST(coalesce((entry ->> 'min-z')::int, 0),
                                                                          coalesce((entry ->> 'max-z')::int, 0))),
                                                       jsonb_build_object(
                                                               'x', GREATEST(coalesce((entry ->> 'min-x')::int, 0),
                                                                             coalesce((entry ->> 'max-x')::int, 0)),
                                                               'z', LEAST(coalesce((entry ->> 'min-z')::int, 0),
                                                                          coalesce((entry ->> 'max-z')::int, 0))),
                                                       jsonb_build_object(
                                                               'x', GREATEST(coalesce((entry ->> 'min-x')::int, 0),
                                                                             coalesce((entry ->> 'max-x')::int, 0)),
                                                               'z', GREATEST(coalesce((entry ->> 'min-z')::int, 0),
                                                                             coalesce((entry ->> 'max-z')::int, 0))),
                                                       jsonb_build_object(
                                                               'x', LEAST(coalesce((entry ->> 'min-x')::int, 0),
                                                                          coalesce((entry ->> 'max-x')::int, 0)),
                                                               'z', GREATEST(coalesce((entry ->> 'min-z')::int, 0),
                                                                             coalesce((entry ->> 'max-z')::int, 0)))))
                                       END
                                   ORDER BY place),
                           '[]'::jsonb)
            INTO converted
            FROM jsonb_array_elements(spawn_areas) WITH ORDINALITY AS t (entry, place);

            DELETE FROM setting_override_node
            WHERE service = 'smp'
              AND name = 'spawn'
              AND path = 'protected-areas';

            INSERT INTO setting_override_node
                (service, name, path, node, parent, field, ordinal, kind, string, number, flag)
            SELECT 'smp', 'spawn', 'protected-areas', n.*
            FROM setting_override_nodes(converted) AS n;
        END IF;
    END IF;
END
$$;
