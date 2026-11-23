-- Border milestones open named overworld regions instead of diameters, and the Nether and the End carry fixed borders.
DO $$
DECLARE
    milestones jsonb;
    updated_milestones jsonb;
    worlds jsonb;
    updated_worlds jsonb;
    overworld text;
    placeholders jsonb := '[
        {"name": "departure", "corners": [{"x": 3958, "z": 2167}, {"x": 4000, "z": 2167}, {"x": 4000, "z": 2209}, {"x": 3958, "z": 2209}]},
        {"name": "foothold", "corners": [{"x": 3929, "z": 2138}, {"x": 4029, "z": 2138}, {"x": 4029, "z": 2238}, {"x": 3929, "z": 2238}]},
        {"name": "settlement", "corners": [{"x": 3779, "z": 1988}, {"x": 4179, "z": 1988}, {"x": 4179, "z": 2388}, {"x": 3779, "z": 2388}]},
        {"name": "expanse", "corners": [{"x": 3529, "z": 1738}, {"x": 4429, "z": 1738}, {"x": 4429, "z": 2638}, {"x": 3529, "z": 2638}]},
        {"name": "frontier", "corners": [{"x": 1979, "z": 188}, {"x": 5979, "z": 188}, {"x": 5979, "z": 4188}, {"x": 1979, "z": 4188}]}
    ]'::jsonb;
    extra_corners jsonb := '[{"x": 1979, "z": 188}, {"x": 5979, "z": 188}, {"x": 5979, "z": 4188}, {"x": 1979, "z": 4188}]'::jsonb;
    nether_world jsonb := '{"world": "nordtal_nether", "enabled": true, "polygons": [{"name": "Nether", "corners": [{"x": -2500, "z": -2500}, {"x": 2499, "z": -2500}, {"x": 2499, "z": 2499}, {"x": -2500, "z": 2499}]}]}'::jsonb;
    end_world jsonb := '{"world": "nordtal_the_end", "enabled": true, "polygons": [{"name": "End", "corners": [{"x": -2500, "z": -2500}, {"x": 2499, "z": -2500}, {"x": 2499, "z": 2499}, {"x": -2500, "z": 2499}]}]}'::jsonb;
BEGIN
    SELECT setting_override_value('smp', 'milestones', 'milestones') INTO milestones;
    IF jsonb_typeof(milestones) = 'array' THEN
        SELECT coalesce(jsonb_agg(
            CASE
                WHEN jsonb_typeof(entry.milestone) <> 'object' THEN entry.milestone
                WHEN upper(btrim(entry.milestone ->> 'unlocks', E' \t\r\n')) = 'BORDER' AND NOT (entry.milestone ? 'border-regions')
                    THEN (entry.milestone - 'border-diameter')
                        || jsonb_build_object(
                            'border-regions',
                            CASE
                                WHEN coalesce(entry.milestone ->> 'key', '') <> ''
                                    THEN jsonb_build_array(entry.milestone ->> 'key')
                                ELSE '[]'::jsonb
                            END)
                WHEN NOT (entry.milestone ? 'border-regions')
                    THEN (entry.milestone - 'border-diameter')
                        || jsonb_build_object('border-regions', '[]'::jsonb)
                ELSE entry.milestone - 'border-diameter'
            END
            ORDER BY entry.place), '[]'::jsonb)
        INTO updated_milestones
        FROM jsonb_array_elements(milestones) WITH ORDINALITY AS entry (milestone, place);

        DELETE FROM setting_override_node
        WHERE service = 'smp' AND name = 'milestones' AND path = 'milestones';

        INSERT INTO setting_override_node (service, name, path, node, parent, field, ordinal, kind, string, number, flag)
        SELECT 'smp', 'milestones', 'milestones', n.*
        FROM setting_override_nodes(updated_milestones) AS n;

        placeholders := placeholders || coalesce((
            SELECT jsonb_agg(
                jsonb_build_object('name', extra.region_name, 'corners', extra_corners)
                ORDER BY extra.first_place)
            FROM (
                SELECT entry.milestone ->> 'key' AS region_name, min(entry.place) AS first_place
                FROM jsonb_array_elements(updated_milestones) WITH ORDINALITY AS entry (milestone, place)
                WHERE jsonb_typeof(entry.milestone) = 'object'
                    AND upper(btrim(entry.milestone ->> 'unlocks', E' \t\r\n')) = 'BORDER'
                    AND coalesce(entry.milestone ->> 'key', '') <> ''
                    AND NOT EXISTS (
                        SELECT 1
                        FROM jsonb_array_elements(placeholders) AS p (polygon)
                        WHERE p.polygon ->> 'name' = entry.milestone ->> 'key'
                    )
                GROUP BY entry.milestone ->> 'key'
            ) AS extra
        ), '[]'::jsonb);
    END IF;

    SELECT coalesce(
        nullif(btrim(setting_override_value('smp', 'worlds', 'world-nordtal') #>> '{}', E' \t\r\n'), ''),
        'nordtal')
    INTO overworld;

    SELECT setting_override_value('smp', 'custom-border', 'worlds') INTO worlds;
    IF jsonb_typeof(worlds) = 'array' THEN
        SELECT coalesce(jsonb_agg(
            CASE
                WHEN jsonb_typeof(entry.world) = 'object' AND entry.world ->> 'world' = overworld THEN
                    jsonb_set(
                        entry.world,
                        '{polygons}',
                        coalesce(
                            CASE jsonb_typeof(entry.world -> 'polygons')
                                WHEN 'array' THEN entry.world -> 'polygons'
                                ELSE '[]'::jsonb
                            END,
                            '[]'::jsonb)
                        || coalesce((
                            SELECT jsonb_agg(p.polygon ORDER BY p.place)
                            FROM jsonb_array_elements(placeholders) WITH ORDINALITY AS p (polygon, place)
                            WHERE NOT EXISTS (
                                SELECT 1
                                FROM jsonb_array_elements(
                                    CASE jsonb_typeof(entry.world -> 'polygons')
                                        WHEN 'array' THEN entry.world -> 'polygons'
                                        ELSE '[]'::jsonb
                                    END) AS existing (polygon)
                                WHERE existing.polygon ->> 'name' = p.polygon ->> 'name'
                            )), '[]'::jsonb))
                ELSE entry.world
            END
            ORDER BY entry.place), '[]'::jsonb)
        INTO updated_worlds
        FROM jsonb_array_elements(worlds) WITH ORDINALITY AS entry (world, place);

        IF NOT EXISTS (
            SELECT 1 FROM jsonb_array_elements(updated_worlds) AS w (world)
            WHERE w.world ->> 'world' = overworld
        ) THEN
            updated_worlds := updated_worlds || jsonb_build_array(
                jsonb_build_object('world', overworld, 'enabled', true, 'polygons', placeholders));
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM jsonb_array_elements(updated_worlds) AS w (world)
            WHERE w.world ->> 'world' = 'nordtal_nether'
        ) THEN
            updated_worlds := updated_worlds || jsonb_build_array(nether_world);
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM jsonb_array_elements(updated_worlds) AS w (world)
            WHERE w.world ->> 'world' = 'nordtal_the_end'
        ) THEN
            updated_worlds := updated_worlds || jsonb_build_array(end_world);
        END IF;

        DELETE FROM setting_override_node
        WHERE service = 'smp' AND name = 'custom-border' AND path = 'worlds';

        INSERT INTO setting_override_node (service, name, path, node, parent, field, ordinal, kind, string, number, flag)
        SELECT 'smp', 'custom-border', 'worlds', n.*
        FROM setting_override_nodes(updated_worlds) AS n;
    END IF;
END
$$;
