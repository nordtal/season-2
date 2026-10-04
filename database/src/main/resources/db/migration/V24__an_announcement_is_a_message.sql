-- An announcement carries one message per language, which the bot renders in that language, so an override of the
-- text reaches a request already written. A request from before keeps its finished text as the words an admin wrote,
-- which post as they are.
UPDATE bot_inbox
SET payload = (payload - 'texts')
                  || jsonb_build_object('messages', (
                          SELECT coalesce(jsonb_object_agg(line.language, jsonb_build_object(
                                          'key', 'announcement.words',
                                          'args', jsonb_build_object('text', jsonb_build_object(
                                                  'kind', 'text', 'value', line.text)))), '{}'::jsonb)
                          FROM jsonb_each_text(payload -> 'texts') AS line(language, text)))
WHERE kind = 'ANNOUNCE'
  AND jsonb_typeof(payload -> 'texts') = 'object';
