-- A run's report tells its notes and each line's detail in messages of the admin bundle, each value typed, so
-- Steward's page and the bot render them for their reader. A report from before keeps its words as report.words,
-- and a row that held only a reason, as an orphaned or restored-over run did, becomes a failed report with that
-- reason as its one note. A report already written in messages is left as it is.
UPDATE steward_inbox
SET outcome = jsonb_build_object(
        'stage', CASE status WHEN 'DONE' THEN 'DONE' WHEN 'CANCELLED' THEN 'CANCELLED' ELSE 'FAILED' END,
        'services', '[]'::jsonb,
        'notes', jsonb_build_array(jsonb_build_object(
                'key', 'report.words',
                'args', jsonb_build_object('text', jsonb_build_object('kind', 'text', 'value', outcome #>> '{}')))))
WHERE jsonb_typeof(outcome) = 'string';

UPDATE steward_inbox
SET outcome = outcome
                  || jsonb_build_object('notes', (
        SELECT coalesce(jsonb_agg(CASE
                                      WHEN jsonb_typeof(note) = 'string' THEN jsonb_build_object(
                                              'key', 'report.words',
                                              'args', jsonb_build_object('text', jsonb_build_object(
                                                      'kind', 'text', 'value', note #>> '{}')))
                                      ELSE note END ORDER BY position), '[]'::jsonb)
        FROM jsonb_array_elements(coalesce(outcome -> 'notes', '[]'::jsonb)) WITH ORDINALITY AS kept(note, position)))
                  || jsonb_build_object('services', (
        SELECT coalesce(jsonb_agg(CASE
                                      WHEN jsonb_typeof(line -> 'detail') = 'string' THEN jsonb_set(
                                              line, '{detail}', jsonb_build_object(
                                                      'key', 'report.words',
                                                      'args', jsonb_build_object('text', jsonb_build_object(
                                                              'kind', 'text', 'value', line ->> 'detail'))))
                                      ELSE line END ORDER BY position), '[]'::jsonb)
        FROM jsonb_array_elements(coalesce(outcome -> 'services', '[]'::jsonb)) WITH ORDINALITY AS kept(line, position)))
WHERE jsonb_typeof(outcome) = 'object';

COMMENT ON COLUMN steward_inbox.outcome IS 'The run''s report: its stage, one line per service and its notes, each note and a line''s detail a message, its key and typed values; null until the run writes one.';
