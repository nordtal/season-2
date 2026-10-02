-- Which release installed the file a server runs for each artefact: a plugin jar, or Paper's or Velocity's own.
-- Images carry their release in their tag, but plugins and the platform jars stay files in the volumes, so a run
-- writes here what it moved into place and steward shows it beside the jar. One row per server and artefact, the
-- file it runs now. steward-agent writes and reads it as the owner, so no other role is granted anything.
CREATE TABLE plugin_file
(
    service      text        NOT NULL CONSTRAINT plugin_file_service_check CHECK (service ~ '^[a-z0-9-]+$'),
    artifact     text        NOT NULL,
    file_name    text        NOT NULL,
    release      text        NOT NULL CONSTRAINT plugin_file_release_check CHECK (length(release) BETWEEN 1 AND 64),
    installed_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT plugin_file_pkey PRIMARY KEY (service, artifact)
);
COMMENT ON TABLE plugin_file IS 'Owned by steward-agent: the file each server runs for an artefact, and the release whose run installed it.';
