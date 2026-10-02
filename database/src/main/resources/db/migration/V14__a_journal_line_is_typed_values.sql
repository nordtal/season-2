-- A journal line names who did it as the actor columns every request table has, and what happened as typed values
-- under their own keys, never as a sentence; Steward renders the line.
ALTER TABLE audit_log
    ADD COLUMN actor_kind varchar(16),
    ADD COLUMN actor_id   varchar(32),
    ADD COLUMN facts      jsonb NOT NULL DEFAULT '{}';

-- A Discord id was a person; the host's word was the host; anything else, and nobody, was the system on its own.
-- The subject is the person a line concerns, a snowflake; a command, a run id or a service there becomes the target.
UPDATE audit_log
SET actor_kind = CASE
                     WHEN actor ~ '^[0-9]+$' THEN 'PERSON'
                     WHEN actor = 'host' THEN 'HOST'
                     ELSE 'STEWARD'
                 END,
    actor_id   = CASE WHEN actor ~ '^[0-9]+$' THEN actor END,
    facts      = jsonb_strip_nulls(jsonb_build_object(
                     'detail', detail,
                     'target', CASE WHEN subject !~ '^[0-9]{15,}$' THEN subject END)),
    subject    = CASE WHEN subject ~ '^[0-9]{15,}$' THEN subject END;

ALTER TABLE audit_log
    ALTER COLUMN actor_kind SET NOT NULL,
    ADD CONSTRAINT audit_log_actor_kind_check CHECK (actor_kind IN ('PERSON', 'STEWARD', 'HOST')),
    ADD CONSTRAINT audit_log_actor_id_iff_person CHECK ((actor_kind = 'PERSON') = (actor_id IS NOT NULL)),
    ADD CONSTRAINT audit_log_facts_object CHECK (jsonb_typeof(facts) = 'object'),
    ADD CONSTRAINT audit_log_subject_is_a_person CHECK (subject ~ '^[0-9]+$'),
    DROP COLUMN actor,
    DROP COLUMN detail;
COMMENT ON TABLE audit_log IS 'Owned by steward, whose journal reads and renders it. Every service that changes access, the phase, a setting, a plugin or a server appends to it and never updates it: an action, a structured actor, the person it concerns and typed facts.';
