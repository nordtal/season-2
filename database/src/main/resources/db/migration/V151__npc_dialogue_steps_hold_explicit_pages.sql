-- Each dialogue step holds an explicit list of pages authored by admins instead of one auto-paginated text.
CREATE FUNCTION pg_temp.migrate_npc_dialogue_pages(dialogue jsonb) RETURNS jsonb
    LANGUAGE sql
AS $$
SELECT CASE
    WHEN dialogue IS NULL OR jsonb_typeof(dialogue -> 'steps') <> 'array' THEN dialogue
    ELSE jsonb_set(
        dialogue,
        '{steps}',
        COALESCE(
            (SELECT jsonb_agg(
                CASE
                    WHEN step ? 'pages' THEN step - 'text'
                    WHEN step ? 'text' THEN (step - 'text') || jsonb_build_object('pages', jsonb_build_array(step -> 'text'))
                    ELSE step
                END
            ) FROM jsonb_array_elements(dialogue -> 'steps') AS step),
            '[]'::jsonb
        )
    )
END;
$$;

UPDATE npc
SET dialogue = pg_temp.migrate_npc_dialogue_pages(dialogue);

UPDATE npc_dialogue_version
SET dialogue = pg_temp.migrate_npc_dialogue_pages(dialogue),
    snapshot = CASE
        WHEN snapshot IS NOT NULL AND snapshot ? 'dialogue'
            THEN jsonb_set(snapshot, '{dialogue}', pg_temp.migrate_npc_dialogue_pages(snapshot -> 'dialogue'))
        ELSE snapshot
    END;
