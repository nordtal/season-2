-- Who added a plugin is the actor columns every request table and the journal have, never the name the interface
-- composed; Steward draws the person from the id.
ALTER TABLE service_plugin
    ADD COLUMN actor_kind varchar(16),
    ADD COLUMN actor_id   varchar(32);

-- The interface wrote "name (Discord id)": the id in the last parentheses is the person. Anything else, and nobody,
-- was Steward on its own, as the journal's own carried lines are.
UPDATE service_plugin
SET actor_kind = CASE WHEN added_by ~ '\(([0-9]+)\)$' THEN 'PERSON' ELSE 'STEWARD' END,
    actor_id   = substring(added_by FROM '\(([0-9]+)\)$');

ALTER TABLE service_plugin
    ALTER COLUMN actor_kind SET NOT NULL,
    ADD CONSTRAINT service_plugin_actor_kind_check CHECK (actor_kind IN ('PERSON', 'STEWARD', 'HOST')),
    ADD CONSTRAINT service_plugin_actor_id_iff_person CHECK ((actor_kind = 'PERSON') = (actor_id IS NOT NULL)),
    DROP COLUMN added_by;
