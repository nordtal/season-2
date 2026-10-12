-- A failed run's alert is titled by the message that titles its card, the run's kind at the stage failed. An alert,
-- and a post the bot has not read yet, that said it in a message of its own keeps its kind under the new one.
UPDATE admin_alert
SET title = jsonb_build_object(
        'key', 'run.title',
        'args', jsonb_build_object(
                'kind', jsonb_build_object('kind', 'choice', 'value', title #>> '{args,kind,value}'),
                'stage', jsonb_build_object('kind', 'choice', 'value', 'failed')))
WHERE title ->> 'key' = 'alert.run-failed';

UPDATE bot_inbox
SET payload = jsonb_set(payload, '{title}', jsonb_build_object(
        'key', 'run.title',
        'args', jsonb_build_object(
                'kind', jsonb_build_object('kind', 'choice', 'value', payload #>> '{title,args,kind,value}'),
                'stage', jsonb_build_object('kind', 'choice', 'value', 'failed'))))
WHERE kind = 'POST_ALERT'
  AND payload #>> '{title,key}' = 'alert.run-failed';
