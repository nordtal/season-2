-- Settings live in the database. Every process publishes the shape of each group of settings it loads,
-- and only an admin's changes are stored, one row per value; a process re-reads on nordtal_settings.

CREATE TABLE setting_group
(
    -- The module that loads the group, such as smp, or network for what every process shares.
    service     varchar(32) NOT NULL CONSTRAINT setting_group_service_check CHECK (service ~ '^[a-z][a-z0-9-]*$'),
    name        varchar(32) NOT NULL CONSTRAINT setting_group_name_check CHECK (name ~ '^[a-z][a-z0-9-]*$'),
    -- The spec's schema tree, as jcore builds it.
    schema      jsonb       NOT NULL,
    -- What the process runs with where no row overrides it, which may differ from the spec's own default.
    defaults    jsonb       NOT NULL,
    -- The paths the process's environment holds, never their values.
    environment text[]      NOT NULL,
    -- Whether a change applies without a restart.
    live        boolean     NOT NULL,
    -- Why the process refused the stored values and kept running on the last ones it took.
    problem     text,
    published   timestamptz NOT NULL,
    CONSTRAINT setting_group_pkey PRIMARY KEY (service, name)
);
COMMENT ON TABLE setting_group IS 'Owned by every process for the groups it loads, published at each start: what each setting is and what it defaults to. steward draws its settings pages from it.';

CREATE TABLE setting_override
(
    service    varchar(32)  NOT NULL,
    name       varchar(32)  NOT NULL,
    -- The dotted path of one value of the group; a list is one value.
    path       varchar(128) NOT NULL CONSTRAINT setting_override_path_check CHECK (length(path) > 0),
    value      jsonb        NOT NULL,
    actor_kind varchar(16)  NOT NULL
        CONSTRAINT setting_override_actor_kind_check CHECK (actor_kind IN ('PERSON', 'STEWARD', 'HOST')),
    actor_id   varchar(32),
    changed    timestamptz  NOT NULL,
    CONSTRAINT setting_override_pkey PRIMARY KEY (service, name, path),
    CONSTRAINT setting_override_group_fkey FOREIGN KEY (service, name) REFERENCES setting_group (service, name),
    CONSTRAINT setting_override_actor_id_iff_person CHECK ((actor_kind = 'PERSON') = (actor_id IS NOT NULL))
);
COMMENT ON TABLE setting_override IS 'Owned by steward, where an admin changes a setting: one row per value that differs from its default. Every process reads its own rows and those of network.';

-- Every process publishes its own groups and reads the overrides; steward changes them. A process inserts
-- an override only when it imports a settings file it finds in its data folder.
GRANT SELECT ON setting_group, setting_override TO ${role_read};
GRANT INSERT, UPDATE ON setting_group
    TO ${role_discord_bot}, ${role_proxy}, ${role_limbo}, ${role_hunger_games}, ${role_smp}, ${role_steward_ui};
GRANT INSERT ON setting_override TO ${role_discord_bot}, ${role_proxy}, ${role_limbo}, ${role_hunger_games}, ${role_smp};
GRANT INSERT, UPDATE, DELETE ON setting_override TO ${role_steward_ui};
