-- season-2-ops/129: a plugin somebody added from the interface, on top of the ones the code gives.
--
-- WHY THIS TABLE HAS TO EXIST AT ALL, and why the feature is not a UI change.
-- `Topology.SERVICES` is a `List.of(...)` in Java. It says that the SMP server runs DisplayTags,
-- and it says it in code because it is not a deployment's decision: the SMP plugin does not enable
-- without DisplayTags. A plugin an admin picks off Modrinth is the opposite kind of fact - it is
-- exactly a deployment's decision, it changes between one Tuesday and the next, and it cannot be
-- written into a jar that is already built. So the two live in two places, and the distinction
-- *fixed* against *added* IS the distinction *code* against *this table*. That is also what makes
-- "the Nordtal plugins cannot be removed" true rather than merely greyed out: there is no row to
-- delete, and `Resolver` merges the two lists on every run.
--
-- NOT a general "installed plugins" registry. What is actually installed is the jars in the
-- volume, and `Installation` reads them off the disk on every run for the reason its own javadoc
-- gives: a record of what *should* be installed is the thing that was wrong on 2026-09-01. This
-- table is a list of WISHES, not of facts - one row means "somebody asked for this, add it to the
-- topology". The jar arriving is a separate event and is observed on disk like every other jar.
CREATE TABLE service_plugin (
    -- The compose service name, the same string `update_request.scope` and `service_hold.service`
    -- carry. No foreign key, because there is no service table - the services are named in
    -- `Topology` in Java and in `compose.yml`, and a registry row per service here would be a third
    -- list that can disagree with those two (the same reasoning V28 wrote down).
    service     text        NOT NULL,

    -- The artefact id: Modrinth's slug, which is what a report line and a jar name are read by.
    -- It is the label; `project_id` is the identity. A project that renames its slug keeps
    -- resolving, because nothing looks it up by this.
    artifact    text        NOT NULL,

    -- Modrinth's immutable project id. This is what `Modrinth#newest` is actually asked with, and
    -- it is the reason a slug rename costs nothing.
    project_id  text        NOT NULL,

    -- The filename prefix the resolved jar carries - `worldedit-bukkit` for
    -- worldedit-bukkit-7.3.0.jar - as `JarName#prefixOf` reads it.
    --
    -- NOT NULL, and that is the decision that makes removal possible at all. Removing a plugin has
    -- to delete a jar, and the only way to know which jar without asking Modrinth again (over the
    -- internet, at the moment somebody presses a button) is to have written the answer down when
    -- it was already in hand. It is filled at add time from the same `Modrinth#newest` call that
    -- proves there is a build for this Minecraft version at all - so a project with no build for
    -- 26.2 is refused while somebody is looking at the refusal, instead of becoming a row that
    -- silently never installs.
    --
    -- The cost, stated: a project that renames its artefact between being added and being removed
    -- leaves its old jar behind. That jar then appears in the plan under `unclaimed`, which is
    -- loud, which is the trade.
    file_prefix text        NOT NULL,

    -- For the interface, and for nothing else. Held here rather than fetched from Modrinth on
    -- every page load: a list of ten plugins must not be ten calls to somebody else's API, and a
    -- title that is one release out of date has never hurt anybody.
    title       text        NOT NULL,
    icon_url    text,
    page_url    text,

    added       timestamptz NOT NULL DEFAULT now(),
    -- Who added it, in the same shape as `update_request.requested_by`. Nullable for the same
    -- reason `service_hold.held_by` is: something that is not a person has nobody to name.
    added_by    text,

    -- One plugin per service, and the same plugin on two services is two rows - they resolve
    -- separately and land in two different volumes.
    PRIMARY KEY (service, artifact),
    CONSTRAINT service_plugin_service_check CHECK (service ~ '^[a-z0-9-]+$')
);

COMMENT ON TABLE service_plugin IS
    'One row per plugin an admin added from the interface (season-2-ops/129). The plugins the '
        'network needs are in Topology.SERVICES in Java and are NOT in here, which is what makes '
        'them unremovable rather than merely greyed out.';
