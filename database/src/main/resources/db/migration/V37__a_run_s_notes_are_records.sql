-- A run's notes are records: the step of the run each is about, its outcome, the service it concerns and the message
-- whose typed values are its values. A note that named several services becomes one record per service, in their
-- order, and a value the record now carries leaves the message. A note in words keeps them, and like a note of a
-- key this table does not know, it is the run's own, failed in a failed run and done in any other.
WITH kinds (key, step, outcome, subject, fixed, dropped) AS (
    VALUES ('report.orphaned', 'RUN', 'FAILED', NULL, NULL, '{}'::text[]),
           ('report.restored-over', 'RUN', 'FAILED', NULL, NULL, '{}'),
           ('report.failed-unexpectedly', 'RUN', 'FAILED', NULL, NULL, '{}'),
           ('report.cancelled', 'RUN', 'SKIPPED', NULL, NULL, '{}'),
           ('report.standbys-ready', 'STANDBY', 'DONE', 'standbys', NULL, '{standbys,run}'),
           ('report.no-standby', 'STANDBY', 'FAILED', NULL, NULL, '{run}'),
           ('report.not-stopped', 'STOP', 'FAILED', 'services', NULL, '{services}'),
           ('report.unverified-stop', 'STOP', 'DOUBT', 'services', NULL, '{services,run,failsTheRun}'),
           ('report.standby-not-started', 'STANDBY', 'FAILED', 'standby', NULL, '{standby}'),
           ('report.standbys-unhealthy', 'STANDBY', 'FAILED', 'standbys', NULL, '{standbys}'),
           ('report.standbys-interrupted', 'STANDBY', 'FAILED', 'standbys', NULL, '{standbys}'),
           ('report.evacuation-interrupted', 'PLAYERS', 'WARNING', 'services', NULL, '{services}'),
           ('report.stopped-with-players', 'PLAYERS', 'WARNING', 'servers', NULL, '{servers,players}'),
           ('report.players-unknown', 'PLAYERS', 'WARNING', 'services', NULL, '{services}'),
           ('report.standby-stopped', 'STANDBY', 'DONE', 'standby', NULL, '{standby}'),
           ('report.standby-stopped-with-players', 'STANDBY', 'WARNING', 'standby', NULL, '{standby}'),
           ('report.standby-stopped-interrupted', 'STANDBY', 'WARNING', 'standby', NULL, '{standby}'),
           ('report.standby-no-container', 'STANDBY', 'FAILED', 'standby', NULL, '{standby}'),
           ('report.standby-not-stopped', 'STANDBY', 'FAILED', 'standby', NULL, '{standby}'),
           ('report.held-left-out', 'SCOPE', 'SKIPPED', 'services', NULL, '{services}'),
           ('report.released-meanwhile', 'RELEASE', 'FAILED', NULL, NULL, '{}'),
           ('report.older-release', 'RELEASE', 'FAILED', NULL, NULL, '{}'),
           ('report.local-builds-kept', 'RELEASE', 'FAILED', 'services', NULL, '{services,count}'),
           ('report.handed', 'RELEASE', 'DONE', NULL, NULL, '{}'),
           ('report.one-shot-not-started', 'RELEASE', 'FAILED', NULL, NULL, '{}'),
           ('report.not-migrated', 'MIGRATE', 'FAILED', NULL, NULL, '{}'),
           ('report.not-installed', 'INSTALL', 'FAILED', NULL, NULL, '{}'),
           ('report.restart-all-held', 'SCOPE', 'SKIPPED', NULL, NULL, '{}'),
           ('report.restart-none-in-scope', 'SCOPE', 'SKIPPED', 'scope', NULL, '{scope}'),
           ('report.held-not-restarted', 'SCOPE', 'SKIPPED', 'services', NULL, '{services}'),
           ('report.backup-unread', 'SCOPE', 'FAILED', NULL, NULL, '{}'),
           ('report.no-backup-volumes', 'SCOPE', 'FAILED', NULL, NULL, '{}'),
           ('report.no-offsite', 'BACKUP', 'SKIPPED', NULL, NULL, '{}'),
           ('report.pruned', 'BACKUP', 'DONE', NULL, NULL, '{}'),
           ('report.pruned-over-budget', 'BACKUP', 'DONE', NULL, NULL, '{}'),
           ('report.backup-wont-fit', 'BACKUP', 'FAILED', NULL, NULL, '{}'),
           ('report.images-pruned', 'CLEANUP', 'DONE', NULL, NULL, '{}'),
           ('report.images-not-pruned', 'CLEANUP', 'WARNING', NULL, NULL, '{}'),
           ('report.down-unnamed', 'SCOPE', 'FAILED', NULL, NULL, '{}'),
           ('report.down-refused', 'SCOPE', 'FAILED', 'services', NULL, '{services}'),
           ('report.held-down', 'STOP', 'DONE', 'services', NULL, '{services}'),
           ('report.nothing-held', 'SCOPE', 'SKIPPED', NULL, NULL, '{}'),
           ('report.remake-unnamed', 'SCOPE', 'FAILED', NULL, NULL, '{kind}'),
           ('report.remake-agent', 'SCOPE', 'FAILED', NULL, 'steward-agent', '{}'),
           ('report.remake-unknown', 'SCOPE', 'FAILED', 'services', NULL, '{services}'),
           ('report.held-not-remade', 'SCOPE', 'SKIPPED', 'services', NULL, '{services}'),
           ('report.removal-unnamed', 'SCOPE', 'FAILED', NULL, NULL, '{}'),
           ('report.removal-unknown', 'SCOPE', 'FAILED', 'service', NULL, '{service}'),
           ('report.removal-empty', 'INSTALL', 'SKIPPED', NULL, NULL, '{}'),
           ('report.removed', 'INSTALL', 'DONE', NULL, NULL, '{}'),
           ('report.removal-failed', 'INSTALL', 'FAILED', NULL, NULL, '{}'),
           ('report.restore-unnamed', 'SCOPE', 'FAILED', NULL, NULL, '{}'),
           ('report.restore-unknown', 'SCOPE', 'FAILED', NULL, NULL, '{}'),
           ('report.restore-not-a-volume', 'SCOPE', 'FAILED', 'volume', NULL, '{volume}'),
           ('report.restore-unsaved', 'BACKUP', 'FAILED', 'volume', NULL, '{volume}'),
           ('report.restore-database-unsaved', 'BACKUP', 'FAILED', NULL, 'database', '{}'),
           ('report.restore-failed', 'INSTALL', 'FAILED', NULL, NULL, '{}'),
           ('report.restore-left-down', 'INSTALL', 'FAILED', 'volume', NULL, '{volume}'),
           ('report.images-unread', 'SOURCES', 'WARNING', NULL, NULL, '{}'),
           ('report.images-uncompared', 'SOURCES', 'WARNING', NULL, NULL, '{}'),
           ('report.images-unverifiable', 'SOURCES', 'WARNING', 'services', NULL, '{services,count}'),
           ('report.images-local', 'SOURCES', 'WARNING', 'services', NULL, '{services,count}'),
           ('report.foreign-newer', 'SOURCES', 'WARNING', 'services', NULL, '{services,count}'),
           ('report.pack-moves', 'SOURCES', 'DONE', NULL, NULL, '{}'),
           ('report.pack-unchecked', 'SOURCES', 'FAILED', NULL, NULL, '{}'),
           ('report.not-in-release', 'SOURCES', 'WARNING', 'service', NULL, '{service}'),
           ('report.unclaimed', 'SOURCES', 'WARNING', 'service', NULL, '{service}'),
           ('report.velocity-ahead', 'SOURCES', 'WARNING', NULL, NULL, '{}')),
     notes AS (
         SELECT run.id, kept.position, kept.note, run.status,
                coalesce(kinds.step, 'RUN') AS step,
                CASE
                    WHEN kinds.outcome = 'DOUBT' AND (kept.note #>> '{args,failsTheRun,value}') = 'true' THEN 'FAILED'
                    WHEN kinds.outcome = 'DOUBT' THEN 'WARNING'
                    WHEN kinds.outcome IS NOT NULL THEN kinds.outcome
                    WHEN run.status = 'FAILED' THEN 'FAILED'
                    ELSE 'DONE' END AS outcome,
                kinds.subject, kinds.fixed,
                coalesce(kept.note -> 'args', '{}'::jsonb) - coalesce(kinds.dropped, '{}') AS args
         FROM steward_inbox AS run
                  CROSS JOIN LATERAL jsonb_array_elements(run.outcome -> 'notes') WITH ORDINALITY AS kept(note, position)
                  LEFT JOIN kinds ON kinds.key = kept.note ->> 'key'
         WHERE jsonb_typeof(run.outcome) = 'object'
           AND jsonb_typeof(run.outcome -> 'notes') = 'array'),
     records AS (
         -- One per element of a list subject; a standby's health was a message naming it, a player count "smp: 3".
         SELECT notes.id, notes.position, part.index,
                notes.step, notes.outcome,
                CASE
                    WHEN notes.note ->> 'key' = 'report.standbys-unhealthy' THEN part.element #>> '{value,args,standby,value}'
                    WHEN notes.note ->> 'key' = 'report.stopped-with-players' THEN split_part(part.element ->> 'value', ': ', 1)
                    ELSE part.element ->> 'value' END AS service,
                notes.note ->> 'key' AS key,
                CASE
                    WHEN notes.note ->> 'key' = 'report.standbys-unhealthy'
                        THEN notes.args || jsonb_build_object('seen', part.element #> '{value,args,seen}')
                    WHEN notes.note ->> 'key' = 'report.stopped-with-players'
                        THEN notes.args || jsonb_build_object('players', jsonb_build_object(
                                'kind', 'number', 'value', split_part(part.element ->> 'value', ': ', 2)::bigint))
                    ELSE notes.args END AS args
         FROM notes
                  CROSS JOIN LATERAL jsonb_array_elements(notes.note #> ARRAY ['args', notes.subject, 'value'])
             WITH ORDINALITY AS part(element, index)
         WHERE notes.note #>> ARRAY ['args', notes.subject, 'kind'] = 'list'
         UNION ALL
         SELECT notes.id, notes.position, 1,
                notes.step, notes.outcome,
                coalesce(notes.note #>> ARRAY ['args', notes.subject, 'value'], notes.fixed),
                notes.note ->> 'key', notes.args
         FROM notes
         WHERE notes.subject IS NULL
            OR coalesce(notes.note #>> ARRAY ['args', notes.subject, 'kind'], '') <> 'list'),
     rebuilt AS (
         SELECT id,
                jsonb_agg(jsonb_build_object('step', step, 'outcome', outcome)
                              || CASE WHEN service IS NULL THEN '{}'::jsonb ELSE jsonb_build_object('service', service) END
                              || jsonb_build_object('what', jsonb_build_object('key', key, 'args', args))
                          ORDER BY position, index) AS notes
         FROM records
         GROUP BY id)
UPDATE steward_inbox
SET outcome = outcome || jsonb_build_object('notes', rebuilt.notes)
FROM rebuilt
WHERE steward_inbox.id = rebuilt.id;

COMMENT ON COLUMN steward_inbox.outcome IS 'The run''s report: its stage, one line per service and its notes, each note a record of its step, outcome, service and message, a message being its key and typed values; null until the run writes one.';
