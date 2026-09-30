-- The whole season 2 schema. Every table names its owning service in its COMMENT; the owner is the
-- service whose code decides what a row means, and a table another service writes says who and how.
--
-- Every point in time is `timestamptz`. A Discord id is varchar(32), a Minecraft account a uuid.
-- Rows that describe what is live now (online_count, online_player, proxy_standby_state) are stamped
-- by the writing process with its own clock, not by now(): every process shares the host's clock,
-- and a writer handed a test clock is what makes staleness testable without sleeping.


-- People

CREATE TABLE discord_user
(
    discord_id                   varchar(32) PRIMARY KEY,
    -- An IETF language tag; English is the default and the fallback.
    locale                       varchar(8)  NOT NULL DEFAULT 'en',
    -- Guild membership as the bot last saw it; a ban refuses the login, it does not pause a period.
    member_state                 varchar(16) NOT NULL DEFAULT 'MEMBER'
        CONSTRAINT discord_user_member_state_check CHECK (member_state IN ('MEMBER', 'LEFT', 'BANNED')),
    -- Permanent once granted; the bot never clears it.
    donor                        boolean     NOT NULL DEFAULT false,
    updated                      timestamptz NOT NULL DEFAULT now(),
    -- Kept equal to "has a grant" by the CHECK below: the proxy, the plugins and the bot read this flag.
    admin                        boolean     NOT NULL DEFAULT false,
    -- A cache of Discord's profile, each value with the instant it was observed.
    discord_username             varchar(32),
    discord_username_updated     timestamptz,
    discord_display_name         varchar(32),
    discord_display_name_updated timestamptz,
    discord_avatar_url           text,
    discord_avatar_url_updated   timestamptz,
    -- The admin tree: who made this person an admin. The one admin without a granter is the root.
    admin_granted_by             varchar(32)
        CONSTRAINT discord_user_admin_granted_by_fkey REFERENCES discord_user (discord_id),
    admin_granted_at             timestamptz,
    -- Who let this person play without the resource pack, and when.
    pack_exempt_by               varchar(32)
        CONSTRAINT discord_user_pack_exempt_by_fkey REFERENCES discord_user (discord_id),
    pack_exempt_at               timestamptz,
    CONSTRAINT discord_user_admin_is_granted CHECK (admin = (admin_granted_at IS NOT NULL)),
    CONSTRAINT discord_user_admin_granter_only_for_admins CHECK (admin OR admin_granted_by IS NULL),
    CONSTRAINT discord_user_admin_not_self_granted CHECK (admin_granted_by IS DISTINCT FROM discord_id),
    CONSTRAINT discord_user_pack_exempt_is_set_by_someone CHECK ((pack_exempt_by IS NULL) = (pack_exempt_at IS NULL))
);
COMMENT ON TABLE discord_user IS 'Owned by discord-bot, which mirrors guild membership, locale and profile. steward-ui writes the admin tree and the pack exemption columns.';

-- One root at most: two sign-ins racing an empty tree would otherwise both become one.
CREATE UNIQUE INDEX discord_user_one_admin_root ON discord_user ((true)) WHERE admin AND admin_granted_by IS NULL;
CREATE INDEX discord_user_admin_granted_by ON discord_user (admin_granted_by) WHERE admin_granted_by IS NOT NULL;

CREATE TABLE account_link
(
    discord_id      varchar(32) PRIMARY KEY
        CONSTRAINT account_link_discord_id_fkey REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    mc_uuid         uuid        NOT NULL CONSTRAINT account_link_mc_uuid_key UNIQUE,
    linked          timestamptz NOT NULL DEFAULT now(),
    -- The name last seen at login: a cache, never a key, since Mojang lets a released name be retaken.
    mc_name         varchar(16),
    mc_name_updated timestamptz
);
COMMENT ON TABLE account_link IS 'Owned by discord-bot, which redeems link codes. The proxy writes mc_name at login.';

CREATE TABLE link_code
(
    code    varchar(16) PRIMARY KEY,
    mc_uuid uuid        NOT NULL CONSTRAINT link_code_mc_uuid_key UNIQUE,
    created timestamptz NOT NULL DEFAULT now(),
    expires timestamptz NOT NULL,
    CONSTRAINT link_code_expires_after_created CHECK (expires > created)
);
COMMENT ON TABLE link_code IS 'Owned by the proxy, which issues a code to an unlinked player at login. discord-bot redeems and sweeps them.';
CREATE INDEX link_code_expires_idx ON link_code (expires);

CREATE TABLE admin_grant
(
    id         bigserial PRIMARY KEY,
    discord_id varchar(32) NOT NULL,
    granted_by varchar(32) NOT NULL,
    granted    timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE admin_grant IS 'Owned by steward-ui: every admin grant ever made, for the limit per hour that the tree itself cannot count once a grant is revoked.';
CREATE INDEX admin_grant_granted ON admin_grant (granted);


-- Paid access

CREATE TABLE payment_request
(
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- What the payer types into the transfer description; the reference match looks for it.
    reference        varchar(16) NOT NULL CONSTRAINT payment_request_reference_key UNIQUE,
    discord_id       varchar(32) NOT NULL
        CONSTRAINT payment_request_discord_id_fkey REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    days             integer     NOT NULL CONSTRAINT payment_request_days_check CHECK (days > 0),
    amount_cents     integer     NOT NULL CONSTRAINT payment_request_amount_cents_check CHECK (amount_cents > 0),
    donation_cents   integer     NOT NULL DEFAULT 0
        CONSTRAINT payment_request_donation_cents_check CHECK (donation_cents >= 0),
    status           varchar(16) NOT NULL DEFAULT 'OPEN'
        CONSTRAINT payment_request_status_check
            CHECK (status IN ('OPEN', 'PAID', 'EXPIRED', 'CANCELLED', 'SUPERSEDED')),
    bunq_tab_id      bigint,
    share_url        text,
    bunq_payment_id  bigint,
    created          timestamptz NOT NULL DEFAULT now(),
    expires          timestamptz NOT NULL,
    settled          timestamptz,
    -- The bank side, written by steward-worker: asked for a tab, the tab failed, cancel asked, cancelled.
    tab_requested    timestamptz,
    tab_failed       text,
    cancel_requested timestamptz,
    tab_cancelled    timestamptz,
    matched_cents    integer,
    matched_by       varchar(16)
        CONSTRAINT payment_request_matched_by_check
            CHECK (matched_by IS NULL OR matched_by IN ('TAB', 'REFERENCE', 'MANUAL')),
    CONSTRAINT payment_request_settled_iff_paid CHECK ((status = 'PAID') = (settled IS NOT NULL))
);
COMMENT ON TABLE payment_request IS 'Owned by discord-bot, which opens, cancels and settles a request. steward-worker writes the bank side: the tab, its failure and the match.';
CREATE UNIQUE INDEX payment_request_bunq_payment_id_key ON payment_request (bunq_payment_id) WHERE bunq_payment_id IS NOT NULL;
CREATE UNIQUE INDEX payment_request_one_open_per_user_key ON payment_request (discord_id) WHERE status = 'OPEN';
CREATE INDEX payment_request_status_idx ON payment_request (status);

CREATE TABLE access_grant
(
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    discord_id         varchar(32) NOT NULL
        CONSTRAINT access_grant_discord_id_fkey REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    valid_from         timestamptz NOT NULL,
    valid_until        timestamptz NOT NULL,
    source             varchar(16) NOT NULL CONSTRAINT access_grant_source_check CHECK (source IN ('PURCHASE', 'ADMIN')),
    payment_request_id uuid
        CONSTRAINT access_grant_payment_request_id_fkey REFERENCES payment_request (id) ON DELETE SET NULL,
    revoked            timestamptz,
    created            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT access_grant_positive_window CHECK (valid_until > valid_from)
);
COMMENT ON TABLE access_grant IS 'Owned by discord-bot: one paid or granted access period. A new period is appended after the last one, which the database clock decides.';
CREATE INDEX access_grant_discord_id_valid_until_idx ON access_grant (discord_id, valid_until);
-- A payment books at most one period.
CREATE UNIQUE INDEX access_grant_payment_request_id_key ON access_grant (payment_request_id) WHERE payment_request_id IS NOT NULL;

CREATE TABLE payment_notice
(
    -- The insert is the deduplication: two overlapping polls cannot both raise the same payment.
    bunq_payment_id bigint PRIMARY KEY,
    reason          varchar(32) NOT NULL,
    detail          text,
    reported        timestamptz NOT NULL DEFAULT now(),
    -- When discord-bot posted it to the admin channel; NULL is still to post.
    posted          timestamptz
);
COMMENT ON TABLE payment_notice IS 'Owned by steward-worker: a bank payment it could not book, raised once. discord-bot posts it and sets posted.';
CREATE INDEX payment_notice_unposted_idx ON payment_notice (reported) WHERE posted IS NULL;

CREATE TABLE expiry_notice
(
    discord_id  varchar(32) NOT NULL
        CONSTRAINT expiry_notice_discord_id_fkey REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    valid_until timestamptz NOT NULL,
    kind        varchar(16) NOT NULL CONSTRAINT expiry_notice_kind_check CHECK (kind IN ('SOON', 'EXPIRED')),
    sent        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT expiry_notice_pkey PRIMARY KEY (discord_id, valid_until, kind)
);
COMMENT ON TABLE expiry_notice IS 'Owned by discord-bot: which access-runs-out message went to whom for which period, so a restart never sends one twice.';

CREATE TABLE payment_gateway
(
    id        boolean     PRIMARY KEY DEFAULT true CONSTRAINT payment_gateway_singleton CHECK (id),
    -- Whether the worker had bunq credentials at its last start; NULL is not said yet, which is not OFF.
    state     varchar(8)  CONSTRAINT payment_gateway_state_check CHECK (state IN ('ON', 'OFF')),
    -- Payments created before this are ignored forever; written once, by the first start that polls.
    watermark timestamptz
);
COMMENT ON TABLE payment_gateway IS 'Owned by steward-worker: whether it can take money, and the cut-off of its payment poll; one row. discord-bot reads the state.';


-- The season

CREATE TABLE season_phase
(
    id        boolean     PRIMARY KEY DEFAULT true CONSTRAINT season_phase_singleton CHECK (id),
    phase     varchar(16) NOT NULL
        CONSTRAINT season_phase_phase_check
            CHECK (phase IN ('PRE_LAUNCH', 'PRE_EVENT', 'START_EVENT', 'SMP', 'MAINTENANCE')),
    updated   timestamptz NOT NULL DEFAULT now(),
    -- When the network opens, and when the smp world opens after the start event; NULL is not set yet.
    launch    timestamptz,
    smp_start timestamptz
);
COMMENT ON TABLE season_phase IS 'Owned by steward-ui, where an admin moves the season on; one row. discord-bot writes it too, from the season commands.';
INSERT INTO season_phase (phase) VALUES ('PRE_LAUNCH');

CREATE TABLE player_playtime
(
    discord_id varchar(32) PRIMARY KEY
        CONSTRAINT player_playtime_discord_id_fkey REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    seconds    bigint      NOT NULL DEFAULT 0 CONSTRAINT player_playtime_seconds_not_negative CHECK (seconds >= 0),
    updated    timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE player_playtime IS 'Owned by the proxy, which adds a session''s seconds when it ends. discord-bot overwrites it on an admin''s request.';

CREATE TABLE network_setting
(
    key     varchar(64) PRIMARY KEY,
    value   text        NOT NULL,
    created timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE network_setting IS 'Owned by the proxy, which publishes the command allowlist here for the Paper servers.';


-- Who is online

CREATE TABLE online_count
(
    -- A compose service name, or "network-control" for the proxy's own total.
    subject text        PRIMARY KEY CONSTRAINT online_count_subject_check CHECK (length(subject) BETWEEN 1 AND 64),
    players integer     NOT NULL CONSTRAINT online_count_players_check CHECK (players >= 0),
    -- A row that stops moving means the writer stopped; the reader decides when that is stale.
    updated timestamptz NOT NULL
);
COMMENT ON TABLE online_count IS 'Owned by the proxy: how many players are on each server now, one row per subject, never a history.';

CREATE TABLE online_player
(
    mc_uuid uuid        PRIMARY KEY,
    mc_name varchar(16) NOT NULL CONSTRAINT online_player_name_check CHECK (length(mc_name) BETWEEN 1 AND 16),
    -- The service the player is on, or NULL for one the proxy has and no backend has yet.
    subject varchar(64) CONSTRAINT online_player_subject_check CHECK (subject IS NULL OR length(subject) BETWEEN 1 AND 64),
    updated timestamptz NOT NULL
);
COMMENT ON TABLE online_player IS 'Owned by the proxy: who is connected now, one row per player, deleted at logout.';

CREATE TABLE proxy_standby_state
(
    only_row   boolean     PRIMARY KEY DEFAULT true CONSTRAINT proxy_standby_state_only_row_check CHECK (only_row),
    players    integer     NOT NULL,
    updated_at timestamptz NOT NULL
);
COMMENT ON TABLE proxy_standby_state IS 'Owned by the proxy: how many players the standby proxy holds while the main one is swapped.';

CREATE TABLE proxy_swap_seat
(
    player_uuid uuid        PRIMARY KEY,
    server      text        NOT NULL,
    recorded_at timestamptz NOT NULL
);
COMMENT ON TABLE proxy_swap_seat IS 'Owned by the proxy: the server each player was on when the proxy was swapped, to send them back.';
CREATE INDEX proxy_swap_seat_recorded_at_idx ON proxy_swap_seat (recorded_at);


-- Hunger Games

CREATE TABLE hg_game
(
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    state            varchar(16) NOT NULL DEFAULT 'REGISTRATION'
        CONSTRAINT hg_game_state_check CHECK (state IN ('REGISTRATION', 'COUNTDOWN', 'RUNNING', 'DECIDED')),
    started          timestamptz,
    ended            timestamptz,
    created          timestamptz NOT NULL DEFAULT now(),
    winner_member_id uuid
);
COMMENT ON TABLE hg_game IS 'Owned by hunger-games, which runs the game. discord-bot opens one on the first registration.';
-- One game open at a time.
CREATE UNIQUE INDEX hg_game_one_open_key ON hg_game ((true)) WHERE state <> 'DECIDED';

CREATE TABLE hg_team
(
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id      uuid        NOT NULL CONSTRAINT hg_team_game_id_fkey REFERENCES hg_game (id) ON DELETE CASCADE,
    name         varchar(15) NOT NULL CONSTRAINT hg_team_name_length_check CHECK (char_length(name) >= 3),
    colour_rgb   integer,
    colour_named varchar(16),
    created      timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE hg_team IS 'Owned by discord-bot, where teams register. hunger-games writes their colours.';
CREATE UNIQUE INDEX hg_team_game_id_name_lower_key ON hg_team (game_id, lower(name));

CREATE TABLE hg_member
(
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id    uuid        NOT NULL CONSTRAINT hg_member_team_id_fkey REFERENCES hg_team (id) ON DELETE CASCADE,
    game_id    uuid        NOT NULL CONSTRAINT hg_member_game_id_fkey REFERENCES hg_game (id) ON DELETE CASCADE,
    discord_id varchar(32) NOT NULL
        CONSTRAINT hg_member_discord_id_fkey REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    state      varchar(16) NOT NULL DEFAULT 'OWNER'
        CONSTRAINT hg_member_state_check CHECK (state IN ('OWNER', 'INVITED', 'ACCEPTED', 'DECLINED')),
    ready      boolean     NOT NULL DEFAULT false,
    created    timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE hg_member IS 'Owned by discord-bot, where players join teams. hunger-games writes ready.';
CREATE INDEX hg_member_team_id_idx ON hg_member (team_id);
-- A person is in one team per game, counting invitations.
CREATE UNIQUE INDEX hg_member_one_active_membership_key ON hg_member (game_id, discord_id)
    WHERE state IN ('OWNER', 'INVITED', 'ACCEPTED');

ALTER TABLE hg_game
    ADD CONSTRAINT hg_game_winner_member_id_fkey FOREIGN KEY (winner_member_id) REFERENCES hg_member (id) ON DELETE SET NULL;

CREATE TABLE hg_event
(
    id        uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id   uuid        NOT NULL CONSTRAINT hg_event_game_id_fkey REFERENCES hg_game (id) ON DELETE CASCADE,
    type      varchar(32) NOT NULL,
    actor_id  uuid        CONSTRAINT hg_event_actor_id_fkey REFERENCES hg_member (id) ON DELETE SET NULL,
    victim_id uuid        CONSTRAINT hg_event_victim_id_fkey REFERENCES hg_member (id) ON DELETE SET NULL,
    detail    text,
    at        timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE hg_event IS 'Owned by hunger-games: what happened in a game, in order.';
CREATE INDEX hg_event_game_id_at_idx ON hg_event (game_id, at);
CREATE INDEX hg_event_type_actor_id_idx ON hg_event (type, actor_id);


-- SMP

CREATE TABLE smp_player
(
    discord_id               varchar(32) PRIMARY KEY
        CONSTRAINT smp_player_discord_id_fkey REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    aura                     integer     NOT NULL DEFAULT 0,
    last_death_world         text,
    last_death_x             integer,
    last_death_y             integer,
    last_death_z             integer,
    hg_winner_reward_granted boolean     NOT NULL DEFAULT false,
    created                  timestamptz NOT NULL DEFAULT now(),
    updated                  timestamptz NOT NULL DEFAULT now(),
    welcome_shown            boolean     NOT NULL DEFAULT false
);
COMMENT ON TABLE smp_player IS 'Owned by smp: a player''s aura, last death and one-time flags.';
CREATE INDEX smp_player_aura_idx ON smp_player (aura DESC);

CREATE TABLE smp_aura_event
(
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    discord_id varchar(32) NOT NULL
        CONSTRAINT smp_aura_event_discord_id_fkey REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    delta      integer     NOT NULL,
    reason     varchar(32) NOT NULL,
    ref        text,
    at         timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE smp_aura_event IS 'Owned by smp: every change to a player''s aura and why.';
CREATE INDEX smp_aura_event_discord_id_at_idx ON smp_aura_event (discord_id, at DESC);

CREATE TABLE smp_milestone
(
    key      text        PRIMARY KEY,
    state    varchar(16) NOT NULL DEFAULT 'LOCKED'
        CONSTRAINT smp_milestone_state_check CHECK (state IN ('LOCKED', 'ACTIVE', 'UNLOCKED')),
    unlocked timestamptz
);
COMMENT ON TABLE smp_milestone IS 'Owned by smp: where the season''s milestones stand.';

CREATE TABLE smp_objective
(
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    milestone_key text        NOT NULL
        CONSTRAINT smp_objective_milestone_key_fkey REFERENCES smp_milestone (key) ON DELETE CASCADE,
    key           text        NOT NULL,
    type          varchar(16) NOT NULL
        CONSTRAINT smp_objective_type_check CHECK (type IN ('HAND_IN', 'STATISTIC', 'ADVANCEMENT')),
    amount        bigint      NOT NULL DEFAULT 0 CONSTRAINT smp_objective_amount_not_negative CHECK (amount >= 0),
    target        bigint      NOT NULL CONSTRAINT smp_objective_target_positive CHECK (target > 0),
    completed     timestamptz,
    CONSTRAINT smp_objective_key_per_milestone UNIQUE (milestone_key, key)
);
COMMENT ON TABLE smp_objective IS 'Owned by smp: what a milestone asks for and how far the server is.';

CREATE TABLE smp_contribution
(
    objective_id uuid        NOT NULL
        CONSTRAINT smp_contribution_objective_id_fkey REFERENCES smp_objective (id) ON DELETE CASCADE,
    discord_id   varchar(32) NOT NULL
        CONSTRAINT smp_contribution_discord_id_fkey REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    amount       bigint      NOT NULL DEFAULT 0 CONSTRAINT smp_contribution_amount_not_negative CHECK (amount >= 0),
    updated      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT smp_contribution_pkey PRIMARY KEY (objective_id, discord_id)
);
COMMENT ON TABLE smp_contribution IS 'Owned by smp: how much each player gave to an objective.';

CREATE TABLE smp_grave
(
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id   varchar(32) NOT NULL
        CONSTRAINT smp_grave_owner_id_fkey REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    world      text        NOT NULL,
    x          integer     NOT NULL,
    y          integer     NOT NULL,
    z          integer     NOT NULL,
    -- The serialised inventory.
    contents   bytea       NOT NULL,
    experience integer     NOT NULL DEFAULT 0 CONSTRAINT smp_grave_experience_not_negative CHECK (experience >= 0),
    created    timestamptz NOT NULL DEFAULT now(),
    looted     timestamptz,
    looted_by  varchar(32) CONSTRAINT smp_grave_looted_by_fkey REFERENCES discord_user (discord_id) ON DELETE SET NULL
);
COMMENT ON TABLE smp_grave IS 'Owned by smp: what a player dropped at death, until someone loots it.';
CREATE INDEX smp_grave_owner_id_created_idx ON smp_grave (owner_id, created DESC);
CREATE INDEX smp_grave_world_idx ON smp_grave (world);

CREATE TABLE smp_poi
(
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text        NOT NULL,
    world      text        NOT NULL,
    x          integer     NOT NULL,
    y          integer     NOT NULL,
    z          integer     NOT NULL,
    created_by varchar(32) NOT NULL
        CONSTRAINT smp_poi_created_by_fkey REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    created    timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE smp_poi IS 'Owned by smp: named places an admin set.';
CREATE INDEX smp_poi_world_idx ON smp_poi (world);

CREATE TABLE smp_spin
(
    discord_id varchar(32) PRIMARY KEY
        CONSTRAINT smp_spin_discord_id_fkey REFERENCES discord_user (discord_id) ON DELETE CASCADE,
    granted    integer     NOT NULL DEFAULT 0 CONSTRAINT smp_spin_granted_not_negative CHECK (granted >= 0),
    used       integer     NOT NULL DEFAULT 0 CONSTRAINT smp_spin_used_not_negative CHECK (used >= 0),
    -- The day the last free spin was granted, in the network's zone.
    last_free  date,
    CONSTRAINT smp_spin_used_within_granted CHECK (used <= granted)
);
COMMENT ON TABLE smp_spin IS 'Owned by smp: the wheel spins each player has and has used.';


-- Runs and services

CREATE TABLE update_request
(
    id           bigserial PRIMARY KEY,
    kind         varchar(16) NOT NULL
        CONSTRAINT update_request_kind_check
            CHECK (kind IN ('UPDATE', 'RESTART', 'BACKUP', 'DOWN', 'START')),
    status       varchar(16) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT update_request_status_check CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED')),
    source       varchar(16) NOT NULL
        CONSTRAINT update_request_source_check CHECK (source IN ('DISCORD', 'GAME', 'CONSOLE')),
    requested_by varchar(64),
    requested    timestamptz NOT NULL DEFAULT now(),
    -- When the run may go ahead: now() when asked, moved forward by the worker once its plan has work.
    not_before   timestamptz NOT NULL DEFAULT now(),
    started      timestamptz,
    finished     timestamptz,
    -- The run's report as JSON, rewritten as the run moves through its stages.
    result       text,
    -- Comma-separated compose services the run is for; NULL is the whole network.
    scope        text CONSTRAINT update_request_scope_check CHECK (scope IS NULL OR scope ~ '^[a-z0-9-]+(,[a-z0-9-]+)*$')
);
COMMENT ON TABLE update_request IS 'Owned by steward-worker, which claims and runs every row. steward-ui, the worker''s own schedule and the host installer ask for runs; steward-ui cancels them.';
CREATE INDEX update_request_pending ON update_request (not_before, id) WHERE status IN ('PENDING', 'RUNNING');

CREATE TABLE service_hold
(
    service    text PRIMARY KEY CONSTRAINT service_hold_service_check CHECK (service ~ '^[a-z0-9-]+$'),
    since      timestamptz NOT NULL DEFAULT now(),
    held_by    text,
    request_id bigint CONSTRAINT service_hold_request_id_fkey REFERENCES update_request (id) ON DELETE SET NULL
);
COMMENT ON TABLE service_hold IS 'Owned by steward-worker: a service deliberately stopped, which stays stopped until someone starts it. No row is the ordinary case.';

CREATE TABLE service_plugin
(
    service     text        NOT NULL CONSTRAINT service_plugin_service_check CHECK (service ~ '^[a-z0-9-]+$'),
    artifact    text        NOT NULL,
    project_id  text        NOT NULL,
    file_prefix text        NOT NULL,
    title       text        NOT NULL,
    icon_url    text,
    page_url    text,
    added       timestamptz NOT NULL DEFAULT now(),
    added_by    text,
    CONSTRAINT service_plugin_pkey PRIMARY KEY (service, artifact)
);
COMMENT ON TABLE service_plugin IS 'Owned by steward-worker: a plugin an admin added. The plugins the network needs are in the topology and never here, which is what makes them unremovable.';

CREATE TABLE metric_sample
(
    -- "host", or a compose service name; never a container id, which changes on every deploy.
    subject    text             NOT NULL CONSTRAINT metric_sample_subject_check CHECK (length(subject) BETWEEN 1 AND 64),
    metric     text             NOT NULL CONSTRAINT metric_sample_metric_check CHECK (length(metric) BETWEEN 1 AND 64),
    -- RAW is one measurement; HOUR is the mean of the raw samples of the hour starting at `at`.
    resolution text             NOT NULL CONSTRAINT metric_sample_resolution_check CHECK (resolution IN ('RAW', 'HOUR')),
    at         timestamptz      NOT NULL,
    -- Finite, or avg() would carry a NaN into the hourly mean that outlives its raw rows.
    value      double precision NOT NULL
        CONSTRAINT metric_sample_finite_check CHECK (value > '-Infinity'::double precision AND value < 'Infinity'::double precision),
    CONSTRAINT metric_sample_pkey PRIMARY KEY (subject, metric, resolution, at),
    CONSTRAINT metric_sample_hour_is_aligned_check
        CHECK (resolution <> 'HOUR' OR at = to_timestamp(floor(extract(epoch FROM at) / 3600) * 3600))
);
COMMENT ON TABLE metric_sample IS 'Owned by steward-worker, which samples every 30 s and compacts to hours after 30 days. steward-ui draws it.';
-- The raw rows in age order, which compaction reads; the primary key cannot answer a query naming no subject.
CREATE INDEX metric_sample_raw_by_age ON metric_sample (at) WHERE resolution = 'RAW';


-- Requests between processes

CREATE TABLE access_request
(
    id           bigserial PRIMARY KEY,
    kind         varchar(16) NOT NULL
        CONSTRAINT access_request_kind_check
            CHECK (kind IN ('GRANT', 'REVOKE', 'SETTLE', 'UNLINK', 'SET_PLAYTIME', 'RELOAD_MESSAGES')),
    status       varchar(16) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT access_request_status_check CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'EXPIRED')),
    subject      varchar(64) NOT NULL,
    argument     varchar(64),
    source       varchar(16) NOT NULL
        CONSTRAINT access_request_source_check CHECK (source IN ('DISCORD', 'STEWARD', 'GAME', 'CONSOLE')),
    requested_by varchar(64),
    requested    timestamptz NOT NULL DEFAULT now(),
    expires      timestamptz NOT NULL,
    started      timestamptz,
    finished     timestamptz,
    result       text
);
COMMENT ON TABLE access_request IS 'Owned by discord-bot, which claims and carries out every row. steward-ui and steward-worker ask.';
CREATE INDEX access_request_pending ON access_request (id) WHERE status = 'PENDING';

CREATE TABLE command_request
(
    id           bigserial PRIMARY KEY,
    target       varchar(16) NOT NULL
        CONSTRAINT command_request_target_check CHECK (target IN ('SMP', 'HUNGER_GAMES', 'LIMBO', 'PROXY', 'BOT')),
    command      varchar(64) NOT NULL,
    arguments    text        NOT NULL DEFAULT '',
    status       varchar(16) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT command_request_status_check CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'EXPIRED')),
    source       varchar(16) NOT NULL
        CONSTRAINT command_request_source_check CHECK (source IN ('DISCORD', 'GAME', 'CONSOLE', 'WEB')),
    requested_by varchar(64) NOT NULL,
    discord_id   varchar(32),
    mc_uuid      uuid,
    locale       varchar(16) NOT NULL DEFAULT 'en',
    requested    timestamptz NOT NULL DEFAULT now(),
    expires      timestamptz NOT NULL,
    started      timestamptz,
    finished     timestamptz,
    result       text,
    CONSTRAINT command_request_console_is_anonymous CHECK (source <> 'CONSOLE' OR (discord_id IS NULL AND mc_uuid IS NULL)),
    CONSTRAINT command_request_discord_knows_who CHECK (source <> 'DISCORD' OR discord_id IS NOT NULL),
    CONSTRAINT command_request_web_knows_who CHECK (source <> 'WEB' OR discord_id IS NOT NULL),
    CONSTRAINT command_request_expired_never_started CHECK (status <> 'EXPIRED' OR started IS NULL),
    CONSTRAINT command_request_finished_after_started CHECK (started IS NULL OR finished IS NULL OR finished >= started),
    CONSTRAINT command_request_finished_iff_settled CHECK ((status IN ('DONE', 'FAILED', 'EXPIRED')) = (finished IS NOT NULL)),
    CONSTRAINT command_request_running_has_started CHECK (status <> 'RUNNING' OR started IS NOT NULL)
);
COMMENT ON TABLE command_request IS 'Owned by the service named in target, which claims its own rows. steward-ui, smp and the command outbox ask; steward-worker deletes old rows.';
CREATE INDEX command_request_pending ON command_request (target, id) WHERE status = 'PENDING';


-- Journal and Discord messages

CREATE TABLE audit_log
(
    id       uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred timestamptz NOT NULL DEFAULT now(),
    action   varchar(32) NOT NULL,
    -- Who did it, a Discord id; NULL is the system.
    actor    varchar(32),
    subject  varchar(32),
    mc_uuid  uuid,
    detail   text
);
COMMENT ON TABLE audit_log IS 'Owned by steward-ui, whose journal reads it. Every service that changes access, the phase or a setting appends to it and never updates it.';
CREATE INDEX audit_log_occurred_idx ON audit_log (occurred);
CREATE INDEX audit_log_subject_idx ON audit_log (subject);

CREATE TABLE managed_message
(
    -- One message per kind, whatever channel the configuration points it at; no CHECK, so a new kind needs no schema change.
    kind       varchar(32) PRIMARY KEY,
    channel_id varchar(32) NOT NULL,
    message_id varchar(32) NOT NULL,
    updated    timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE managed_message IS 'Owned by discord-bot: the messages it keeps posted and edits in place.';


-- Steward

CREATE TABLE steward_session
(
    -- The cookie value itself, 256 random bits: every row is a credential.
    id                  text        PRIMARY KEY CONSTRAINT steward_session_id_check CHECK (length(id) BETWEEN 32 AND 128),
    -- NULL until the Discord sign-in completes.
    discord_id          text,
    display_name        text,
    -- The roles held at sign-in, comma-separated.
    roles               text,
    oauth_state         text,
    csrf                text        NOT NULL CONSTRAINT steward_session_csrf_check CHECK (length(csrf) BETWEEN 16 AND 128),
    created_at          timestamptz NOT NULL,
    -- Absolute, set once at sign-in.
    expires_at          timestamptz NOT NULL,
    -- When this browser last proved a security key; NULL forces the key again without ending the session.
    verified_at         timestamptz,
    -- The WebAuthn ceremony in flight, as the library's JSON, cleared when redeemed.
    webauthn_request    text,
    webauthn_started_at timestamptz,
    CONSTRAINT steward_session_lifetime_check CHECK (expires_at > created_at),
    CONSTRAINT steward_session_signed_in_check CHECK ((discord_id IS NULL) = (display_name IS NULL))
);
COMMENT ON TABLE steward_session IS 'Owned by steward-ui: one signed-in browser.';
CREATE INDEX steward_session_by_expiry ON steward_session (expires_at);

CREATE TABLE steward_credential
(
    credential_id   bytea       PRIMARY KEY,
    discord_id      text        NOT NULL,
    public_key      bytea       NOT NULL,
    -- Clone detection: never goes backwards; 0 forever means the authenticator keeps no counter.
    signature_count bigint      NOT NULL DEFAULT 0 CONSTRAINT steward_credential_counter_check CHECK (signature_count >= 0),
    label           text        NOT NULL CONSTRAINT steward_credential_label_check CHECK (length(btrim(label)) BETWEEN 1 AND 64),
    transports      text,
    backup_eligible boolean,
    backed_up       boolean,
    created_at      timestamptz NOT NULL,
    last_used_at    timestamptz
);
COMMENT ON TABLE steward_credential IS 'Owned by steward-ui: one registered security key. An account with none cannot use Steward.';
CREATE INDEX steward_credential_by_account ON steward_credential (discord_id);

CREATE TABLE steward_push_subscription
(
    -- The push service's URL for this subscription; names a browser, never a person.
    endpoint     text        PRIMARY KEY,
    discord_id   text        NOT NULL,
    p256dh       text        NOT NULL,
    auth         text        NOT NULL,
    created_at   timestamptz NOT NULL,
    last_sent_at timestamptz,
    -- What to call this browser in a list, from its User-Agent.
    device       text
);
COMMENT ON TABLE steward_push_subscription IS 'Owned by steward-ui: one browser''s Web Push subscription, deleted when the push service answers 404 or 410.';
CREATE INDEX steward_push_subscription_by_account ON steward_push_subscription (discord_id);

CREATE TABLE steward_push_preference
(
    discord_id text        NOT NULL,
    -- The alert type's lowercase name; a missing row is the type's own default, never "off".
    alert_type text        NOT NULL,
    enabled    boolean     NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT steward_push_preference_pkey PRIMARY KEY (discord_id, alert_type)
);
COMMENT ON TABLE steward_push_preference IS 'Owned by steward-ui: which alert types one account wants pushed.';
