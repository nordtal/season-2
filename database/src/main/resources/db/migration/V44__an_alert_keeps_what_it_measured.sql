-- An alert keeps what it measured as typed values beside the messages that tell it, so alerts can be filtered and
-- compared over time. An alert raised before reads them back out of its messages' arguments; one about an event or
-- about something missing measured nothing.
ALTER TABLE admin_alert
    ADD COLUMN measured jsonb NOT NULL DEFAULT '[]'
        CONSTRAINT admin_alert_measured_is_a_list CHECK (jsonb_typeof(measured) = 'array');
COMMENT ON COLUMN admin_alert.measured IS 'What the alert measured, each a gauge, its subject and the value, limit or state read; empty for none.';

-- The value of argument arg of the first line of detail with message key.
CREATE FUNCTION pg_temp.detail_arg(detail jsonb, key text, arg text) RETURNS jsonb
    LANGUAGE sql IMMUTABLE AS $$
SELECT line -> 'args' -> arg -> 'value'
FROM jsonb_array_elements(detail) AS line
WHERE line ->> 'key' = key
LIMIT 1
$$;

-- One measure, without the fields it leaves empty.
CREATE FUNCTION pg_temp.measure(gauge text, subject jsonb, value jsonb, lim jsonb, state jsonb) RETURNS jsonb
    LANGUAGE sql IMMUTABLE AS $$
SELECT jsonb_strip_nulls(jsonb_build_object(
        'gauge', gauge, 'subject', subject, 'value', value, 'limit', lim, 'state', state))
$$;

-- What an alert told in title and detail measured; several at once measured what each of their titles did.
CREATE FUNCTION pg_temp.measured_of(title jsonb, detail jsonb) RETURNS jsonb
    LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    args  jsonb := title -> 'args';
    hours jsonb := args #> '{hours,value}';
    age   jsonb := pg_temp.detail_arg(detail, 'alert.permitted-age', 'hours');
BEGIN
    RETURN CASE title ->> 'key'
        WHEN 'alert.not-running' THEN jsonb_build_array(pg_temp.measure('CONTAINER_STATE',
                args #> '{service,value}', NULL, NULL, pg_temp.detail_arg(detail, 'alert.docker-state', 'state')))
        WHEN 'alert.unhealthy' THEN jsonb_build_array(pg_temp.measure('HEALTH',
                args #> '{service,value}', NULL, NULL, to_jsonb('unhealthy'::text)))
        WHEN 'alert.failed-once' THEN jsonb_build_array(pg_temp.measure('EXIT_CODE',
                args #> '{service,value}', args #> '{code,value}', NULL, NULL))
        WHEN 'alert.older-image' THEN (
            SELECT coalesce(jsonb_agg(pg_temp.measure('OLDER_IMAGE', service -> 'value', NULL, NULL, NULL)
                                      ORDER BY position), '[]')
            FROM jsonb_array_elements(coalesce(args #> '{services,value}', '[]')) WITH ORDINALITY
                AS services(service, position))
        WHEN 'alert.old-dump' THEN jsonb_build_array(pg_temp.measure('BACKUP_AGE_HOURS',
                to_jsonb('database dump'::text), hours, age, NULL))
        WHEN 'alert.old-offsite' THEN jsonb_build_array(pg_temp.measure('BACKUP_AGE_HOURS',
                to_jsonb('offsite copy'::text), hours, age, NULL))
        WHEN 'alert.old-archive' THEN jsonb_build_array(pg_temp.measure('BACKUP_AGE_HOURS',
                args #> '{volume,value}', hours, age, NULL))
        WHEN 'alert.disk' THEN jsonb_build_array(pg_temp.measure('DISK_PERCENT', to_jsonb('disk'::text),
                args #> '{percent,value}', pg_temp.detail_arg(detail, 'alert.threshold', 'percent'), NULL))
        WHEN 'alert.memory' THEN jsonb_build_array(pg_temp.measure('MEMORY_PERCENT', to_jsonb('memory'::text),
                args #> '{percent,value}', pg_temp.detail_arg(detail, 'alert.threshold', 'percent'), NULL))
        WHEN 'alert.several' THEN (
            SELECT coalesce(jsonb_agg(measure ORDER BY line_position, measure_position), '[]')
            FROM jsonb_array_elements(detail) WITH ORDINALITY AS lines(line, line_position),
                 jsonb_array_elements(pg_temp.measured_of(line, '[]')) WITH ORDINALITY
                     AS measures(measure, measure_position))
        ELSE '[]'::jsonb
    END;
END
$$;

UPDATE admin_alert
SET measured = pg_temp.measured_of(title, detail)
WHERE pg_temp.measured_of(title, detail) <> '[]';
