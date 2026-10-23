-- A run's request is its kind, the row of its kind where the kind names more, and its services in run_service. The
-- payload, the outcome and the moving array are no longer read by this release; the previous release, which runs
-- beside it during an update, still reads them, so they go with the next one. A new row has no payload.
CREATE TABLE steward_update
(
    request        bigint  PRIMARY KEY
        CONSTRAINT steward_update_request_fkey REFERENCES steward_inbox (id) ON DELETE CASCADE,
    replaces_local boolean NOT NULL
);
COMMENT ON TABLE steward_update IS 'Owned by steward-agent, which claims its inbox: whether an UPDATE row of steward_inbox replaces builds made on the host and in no release.';

CREATE TABLE steward_restore
(
    request bigint PRIMARY KEY
        CONSTRAINT steward_restore_request_fkey REFERENCES steward_inbox (id) ON DELETE CASCADE,
    archive text   NOT NULL
        CONSTRAINT steward_restore_archive_check CHECK (archive <> '' AND archive NOT LIKE '%/%')
);
COMMENT ON TABLE steward_restore IS 'Owned by steward-agent, which claims its inbox: the file name of the archive a RESTORE row of steward_inbox puts back.';

CREATE TABLE steward_remove_plugin
(
    request  bigint       PRIMARY KEY
        CONSTRAINT steward_remove_plugin_request_fkey REFERENCES steward_inbox (id) ON DELETE CASCADE,
    artifact varchar(100) NOT NULL
        CONSTRAINT steward_remove_plugin_artifact_check CHECK (artifact <> '' AND artifact NOT LIKE '%/%')
);
COMMENT ON TABLE steward_remove_plugin IS 'Owned by steward-agent, which claims its inbox: the artefact id of the plugin a REMOVE_PLUGIN row of steward_inbox removes.';

INSERT INTO steward_update (request, replaces_local)
SELECT id, coalesce((payload ->> 'replacesLocal')::boolean, false) FROM steward_inbox WHERE kind = 'UPDATE';
INSERT INTO steward_restore (request, archive)
SELECT id, payload ->> 'archive' FROM steward_inbox WHERE kind = 'RESTORE';
INSERT INTO steward_remove_plugin (request, artifact)
SELECT id, payload ->> 'artifact' FROM steward_inbox WHERE kind = 'REMOVE_PLUGIN';

ALTER TABLE run_service ADD COLUMN named boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN run_service.named IS 'Whether the request names it, which puts it in the run''s scope.';
INSERT INTO run_service (run, service, moving, named)
SELECT id, service, false, true FROM steward_inbox, jsonb_array_elements_text(payload -> 'services') AS named (service)
ON CONFLICT (run, service) DO UPDATE SET named = true;
-- The previous release wrote no rows for a run while its one-shot ran ahead of the schema.
INSERT INTO run_service (run, service, moving)
SELECT id, service, true FROM steward_inbox, unnest(moving) AS stopped (service)
ON CONFLICT (run, service) DO UPDATE SET moving = true;

ALTER TABLE steward_inbox ALTER COLUMN payload DROP NOT NULL;
ALTER TABLE steward_inbox DROP CONSTRAINT steward_inbox_services_check;

GRANT SELECT ON steward_update, steward_restore, steward_remove_plugin TO ${role_read};
GRANT INSERT ON steward_update, steward_restore, steward_remove_plugin TO ${role_steward_ui};
