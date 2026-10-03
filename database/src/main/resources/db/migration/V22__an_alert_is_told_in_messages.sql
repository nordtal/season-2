-- An alert is told in messages of the admin bundle: its title one, the lines below it a list, each with its values
-- typed, so a lock screen, the admin channel and Steward's page render it for their reader. A row from before keeps
-- its words as alert.words: the title, and the detail as one line unless it was empty.
ALTER TABLE admin_alert
    ALTER COLUMN title TYPE jsonb USING jsonb_build_object(
            'key', 'alert.words',
            'args', jsonb_build_object('text', jsonb_build_object('kind', 'text', 'value', title))),
    ALTER COLUMN detail TYPE jsonb USING CASE
        WHEN detail = '' THEN '[]'::jsonb
        ELSE jsonb_build_array(jsonb_build_object(
                'key', 'alert.words',
                'args', jsonb_build_object('text', jsonb_build_object('kind', 'text', 'value', detail))))
        END,
    ADD CONSTRAINT admin_alert_title_is_a_message
        CHECK (coalesce(jsonb_typeof(title -> 'key') = 'string' AND jsonb_typeof(title -> 'args') = 'object', false)),
    ADD CONSTRAINT admin_alert_detail_is_a_list CHECK (jsonb_typeof(detail) = 'array');
COMMENT ON COLUMN admin_alert.title IS 'One line, which a lock screen shows: a message, its key and typed values.';
COMMENT ON COLUMN admin_alert.detail IS 'The lines below the title, each a message; empty for none.';

-- A request to post an alert that the bot has not yet read, or has, carries the same shape: the words it had become
-- one message each, and the link that was the detail's last line stays in its words.
UPDATE bot_inbox
SET payload = payload
                  || jsonb_build_object(
                          'title', jsonb_build_object(
                                  'key', 'alert.words',
                                  'args', jsonb_build_object('text', jsonb_build_object(
                                          'kind', 'text', 'value', payload ->> 'title'))),
                          'detail', CASE
                              WHEN coalesce(payload ->> 'detail', '') = '' THEN '[]'::jsonb
                              ELSE jsonb_build_array(jsonb_build_object(
                                      'key', 'alert.words',
                                      'args', jsonb_build_object('text', jsonb_build_object(
                                              'kind', 'text', 'value', payload ->> 'detail'))))
                              END)
WHERE kind = 'POST_ALERT'
  AND jsonb_typeof(payload -> 'title') = 'string';
