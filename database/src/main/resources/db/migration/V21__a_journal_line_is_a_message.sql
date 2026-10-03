-- A journal line is a message: its key, one per action, and each value with its kind, which Steward's page and the
-- bot render for their reader. A line from before typed values keeps its one sentence as journal.written.
ALTER TABLE audit_log
    ADD COLUMN line jsonb;

-- Each typed fact becomes a value of its kind, as MessageJson writes one, under the name the text uses: a text, an
-- ISO instant, a number, a choice for a flag or for an enum constant (lowercase, hyphenated, as Java chooses on it), a
-- list of texts. Play time was counted in seconds and is a duration now. The key follows the action, and a phase or a
-- date line takes the key of its variant, so a line written since V14 renders as its action does today. A line from
-- before typed values, or a server's request that named only its target, keeps its words: the target, then the
-- sentence.
UPDATE audit_log
SET line = CASE
               WHEN facts ? 'detail' OR facts ? 'target' THEN jsonb_build_object(
                       'key', 'journal.written',
                       'args', jsonb_build_object('detail', jsonb_build_object(
                               'kind', 'text',
                               'value', concat_ws(': ', facts ->> 'target', facts ->> 'detail'))))
               ELSE jsonb_build_object(
                       'key', 'journal.' || CASE
                           WHEN action = 'SET_PHASE' AND facts ? 'reason' THEN 'set-phase-because'
                           WHEN action = 'SET_LAUNCH' AND NOT facts ? 'to' THEN 'clear-launch'
                           WHEN action = 'SET_SMP_START' AND NOT facts ? 'to' THEN 'clear-smp-start'
                           ELSE lower(replace(action, '_', '-')) END,
                       'args', coalesce((SELECT jsonb_object_agg(fact.name, CASE
                           WHEN action = 'SET_PLAYTIME' AND fact.name = 'seconds'
                               THEN jsonb_build_object('kind', 'duration', 'value', fact.value)
                           WHEN fact.name IN ('kind', 'from', 'to') AND fact.value #>> '{}' ~ '^[A-Z][A-Z_]*$'
                               THEN jsonb_build_object('kind', 'choice',
                                                       'value', lower(replace(fact.value #>> '{}', '_', '-')))
                           ELSE CASE jsonb_typeof(fact.value)
                               WHEN 'string' THEN jsonb_build_object(
                                       'kind', CASE WHEN fact.value #>> '{}' ~ '^\d{4}-\d{2}-\d{2}T' THEN 'instant'
                                                    ELSE 'text' END,
                                       'value', fact.value)
                               WHEN 'number' THEN jsonb_build_object('kind', 'number', 'value', fact.value)
                               WHEN 'boolean' THEN jsonb_build_object('kind', 'choice', 'value', fact.value)
                               WHEN 'array' THEN jsonb_build_object('kind', 'list', 'value', (
                                       SELECT coalesce(jsonb_agg(jsonb_build_object(
                                                       'kind', 'text', 'value', item #>> '{}')), '[]')
                                       FROM jsonb_array_elements(fact.value) AS item))
                               ELSE jsonb_build_object('kind', 'text', 'value', fact.value #>> '{}')
                               END
                           END)
                                         FROM jsonb_each(facts) AS fact(name, value)), '{}')
                           || CASE WHEN action = 'REVOKE_ADMIN' AND jsonb_typeof(facts -> 'below') = 'array'
                                   THEN jsonb_build_object('count', jsonb_build_object(
                                           'kind', 'number', 'value', jsonb_array_length(facts -> 'below')))
                                   ELSE '{}' END)
           END;

ALTER TABLE audit_log
    ALTER COLUMN line SET NOT NULL,
    ADD CONSTRAINT audit_log_line_is_a_message
        CHECK (coalesce(jsonb_typeof(line -> 'key') = 'string' AND jsonb_typeof(line -> 'args') = 'object', false)),
    DROP CONSTRAINT audit_log_facts_object,
    DROP COLUMN facts;
COMMENT ON TABLE audit_log IS 'Owned by steward, whose journal reads it. Every service that changes access, the phase, a setting, a plugin or a server appends to it and never updates it: an action, a structured actor, the person it concerns and the line as a message, its key and typed values.';
