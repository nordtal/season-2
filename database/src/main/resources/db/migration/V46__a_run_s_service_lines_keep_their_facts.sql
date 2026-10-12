-- A run's service line keeps the facts its message was composed from: the step, the image, the reason, what the
-- service was left on, whether it came back, the minutes waited, what the runtime showed and the archive with its
-- mark. Each surface draws the message from them. A line in words names its step where the words or its changes
-- tell it, and an archive put back is restored, not installed. What a source answered about a service of a plan is
-- a record of the sources, failed, about that service, ahead of the run's other records.
CREATE FUNCTION pg_temp.seen(said jsonb) RETURNS jsonb
    LANGUAGE sql IMMUTABLE AS
$$
SELECT CASE
           WHEN coalesce(said #>> '{value,args,text,value}', said ->> 'value') = 'no container' THEN '{}'::jsonb
           WHEN said ->> 'kind' = 'text' OR said #>> '{value,key}' = 'report.words' THEN
               jsonb_strip_nulls(jsonb_build_object(
                       'status', split_part(coalesce(said #>> '{value,args,text,value}', said ->> 'value'), ', ', 1),
                       'health', nullif(substring(coalesce(said #>> '{value,args,text,value}', said ->> 'value')
                                                  FROM ', (.*)$'), '')))
           WHEN said #>> '{value,key}' = 'report.runtime-unread' THEN
               jsonb_build_object('unread', said #>> '{value,args,answer,value}')
           ELSE '{}'::jsonb END
$$;

WITH lines AS (
    SELECT run.id, kept.position, kept.line,
           kept.line #>> '{detail,key}' AS key,
           coalesce(kept.line #> '{detail,args}', '{}'::jsonb) AS args,
           EXISTS (SELECT FROM jsonb_array_elements(kept.line -> 'changes') AS change
                   WHERE change ->> 'artefact' = 'restore') AS restore,
           EXISTS (SELECT FROM jsonb_array_elements(kept.line -> 'changes') AS change
                   WHERE change ->> 'artefact' = 'backup') AS backup
    FROM steward_inbox AS run
             CROSS JOIN LATERAL jsonb_array_elements(run.outcome -> 'services') WITH ORDINALITY AS kept(line, position)
    WHERE jsonb_typeof(run.outcome) = 'object'
      AND jsonb_typeof(run.outcome -> 'services') = 'array'),
     typed AS (
         SELECT id, position, line, restore,
                key IS NOT NULL AND NOT key IN (
                    'report.words', 'report.no-container-to-stop', 'report.stop-failed', 'report.no-container-to-start',
                    'report.start-failed', 'report.recreating', 'report.renewing-agent', 'report.agent-not-renewed',
                    'report.foreign-not-recreated', 'report.not-recreated', 'report.fell-back',
                    'report.fell-back-healthy', 'report.fell-back-down', 'report.fell-back-interrupted',
                    'report.not-fell-back', 'report.not-healthy', 'report.wait-interrupted', 'report.not-installed',
                    'report.unverified-archive', 'report.unmarked-archive') AS sourced,
                CASE
                    WHEN key IS NULL THEN NULL
                    WHEN key = 'report.no-container-to-stop' THEN jsonb_build_object('act', 'STOP')
                    WHEN key = 'report.stop-failed' THEN
                        jsonb_build_object('act', 'STOP', 'reason', args #>> '{reason,value}')
                    WHEN key = 'report.no-container-to-start' THEN jsonb_build_object('act', 'START')
                    WHEN key = 'report.start-failed' THEN
                        jsonb_build_object('act', 'START', 'reason', args #>> '{reason,value}')
                    WHEN key = 'report.recreating' THEN
                        jsonb_build_object('act', 'RECREATE', 'image',
                                           CASE WHEN args #>> '{pull,value}' = 'true' THEN 'PULLED' ELSE 'KEPT' END)
                    WHEN key = 'report.renewing-agent' THEN jsonb_build_object('act', 'RENEW')
                    WHEN key = 'report.agent-not-renewed' THEN
                        jsonb_build_object('act', 'RENEW', 'reason', args #>> '{reason,value}')
                    WHEN key = 'report.foreign-not-recreated' THEN
                        jsonb_build_object('act', 'RECREATE', 'reason', args #>> '{reason,value}', 'fallback', 'RUNNING')
                    WHEN key IN ('report.not-recreated', 'report.fell-back', 'report.fell-back-healthy',
                                 'report.fell-back-down', 'report.fell-back-interrupted', 'report.not-fell-back') THEN
                        jsonb_build_object(
                                'act', 'RECREATE',
                                'image', CASE WHEN args #>> '{outdated,value}' = 'true' THEN 'OUTDATED' END,
                                'reason', args #>> '{reason,value}',
                                'fallback', CASE key
                                                WHEN 'report.not-recreated' THEN 'NONE'
                                                WHEN 'report.not-fell-back' THEN 'REFUSED'
                                                ELSE 'STARTED' END,
                                'fallbackReason', args #>> '{answer,value}',
                                'back', CASE key
                                            WHEN 'report.fell-back-healthy' THEN 'YES'
                                            WHEN 'report.fell-back-down' THEN 'NO'
                                            WHEN 'report.fell-back-interrupted' THEN 'INTERRUPTED' END,
                                'waited', (args #>> '{minutes,value}')::bigint,
                                'seen', CASE WHEN args ? 'seen' THEN pg_temp.seen(args -> 'seen') END)
                    WHEN key = 'report.not-healthy' THEN
                        jsonb_build_object('act', 'VERIFY', 'back', 'NO', 'waited', (args #>> '{minutes,value}')::bigint,
                                           'seen', pg_temp.seen(args -> 'seen'))
                    WHEN key = 'report.wait-interrupted' THEN jsonb_build_object('act', 'VERIFY', 'back', 'INTERRUPTED')
                    WHEN key = 'report.not-installed' THEN
                        jsonb_build_object('act', 'INSTALL', 'artefact', args #>> '{artefact,value}',
                                           'reason', args #>> '{reason,value}')
                    WHEN key = 'report.unverified-archive' THEN
                        jsonb_build_object('act', 'BACKUP', 'mark', 'WRITTEN',
                                           'archive', regexp_replace(args #>> '{mark,value}', '\.unverified$', ''))
                    WHEN key = 'report.unmarked-archive' THEN jsonb_build_object('act', 'BACKUP', 'mark', 'NOT_WRITTEN')
                    WHEN key = 'report.words' AND args #>> '{text,value}' = 'pulling its image and recreating the container'
                        THEN jsonb_build_object('act', 'RECREATE', 'image', 'PULLED')
                    WHEN key = 'report.words' AND args #>> '{text,value}' = 'recreating the container'
                        THEN jsonb_build_object('act', 'RECREATE', 'image', 'KEPT')
                    WHEN key = 'report.words' AND args #>> '{text,value}' = 'recreating the container at this release'
                        THEN jsonb_build_object('act', 'RENEW')
                    ELSE jsonb_build_object(
                            'act', CASE
                                       WHEN line ->> 'state' = 'STOPPED' THEN 'STOP'
                                       WHEN restore THEN 'RESTORE'
                                       WHEN backup THEN 'BACKUP' END,
                            'reason', coalesce(args #>> '{text,value}', key)) END AS facts
         FROM lines),
     rebuilt AS (
         SELECT id,
                jsonb_agg(
                        (line - 'detail')
                            || CASE WHEN facts IS NULL OR sourced THEN '{}'::jsonb
                                    ELSE jsonb_build_object('facts', jsonb_strip_nulls(facts)) END
                            || CASE WHEN restore AND line ->> 'state' = 'INSTALLED'
                                        THEN '{"state": "RESTORED"}'::jsonb
                                    ELSE '{}'::jsonb END
                        ORDER BY position) AS services,
                coalesce(jsonb_agg(jsonb_build_object('step', 'SOURCES', 'outcome', 'FAILED',
                                                      'service', line ->> 'service', 'what', line -> 'detail')
                                   ORDER BY position) FILTER (WHERE sourced), '[]'::jsonb) AS sourced
         FROM typed
         GROUP BY id)
UPDATE steward_inbox AS run
SET outcome = jsonb_set(jsonb_set(run.outcome, '{services}', rebuilt.services), '{notes}',
                        rebuilt.sourced || coalesce(run.outcome -> 'notes', '[]'::jsonb))
FROM rebuilt
WHERE run.id = rebuilt.id;

DROP FUNCTION pg_temp.seen(jsonb);
