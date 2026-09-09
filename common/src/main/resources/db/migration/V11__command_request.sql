-- One admin command, asked for on a surface whose process cannot carry it out, addressed to the
-- process that can.
--
-- A second request table next to `update_request` because the two have different lifetimes: an
-- update request is an operational event worth keeping, a command request is a message in flight.
-- Folding them together would mean a nullable column that is null for every row that matters, which
-- is where a constraint stops being able to say anything.
--
-- A command travels because its effect has an address - `/smp aura` has to run in the JVM with the
-- SMP world open. The row plus a `pg_notify` is how it reaches that process, and it survives a
-- target that happens to be restarting.
--
-- No `interval` anywhere, for the reason V4 and V7 give: `expires` is written as an absolute
-- instant.
CREATE TABLE command_request
(
    id           bigserial PRIMARY KEY,

    -- Which process runs the effect. The five are `eu.nordtal.s2.commands.Target`, and
    -- TargetSchemaTest holds the enum against this CHECK.
    target       varchar(16) NOT NULL
        CONSTRAINT command_request_target_check
            CHECK (target IN ('SMP', 'HUNGER_GAMES', 'LIMBO', 'PROXY', 'BOT')),

    -- The command's path, joined with spaces and without the leading slash: `smp aura`, `hg start`.
    -- That is `Declaration#path`, the command's identity on every surface.
    command      varchar(64) NOT NULL,

    -- The arguments, as the line that would have been typed after the path. Empty for a command
    -- that takes none.
    --
    -- A line and not JSON: the declaration makes splitting on spaces unambiguous (at most one
    -- argument is greedy and it must be last), and `:common` deliberately has no JSON parser -
    -- gson is a platform library that must never be shaded into a Paper plugin.
    arguments    text        NOT NULL DEFAULT '',

    -- PENDING -> RUNNING -> DONE | FAILED, or PENDING -> EXPIRED. Nothing goes back.
    --
    -- EXPIRED is written by the ASKING side when it stops waiting, not by a sweeper: the only
    -- process that cares whether an answer came is the one still holding an interaction open. The
    -- target guards the same boundary by refusing to claim a row whose `expires` has passed, so a
    -- slow target and a giving-up asker cannot both act on one row.
    --
    -- Retention: a settled row is deleted 30 days after it finished, because it carries identifiers
    -- rather than because of volume. The updater does it once at the start of `serve` and never on a
    -- timer. A PENDING or RUNNING row is never touched however old it looks.
    status       varchar(16) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT command_request_status_check
            CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'EXPIRED')),

    -- Which surface asked. Recorded for the audit trail and never used to authorise: authorisation
    -- is `discord_user.admin`, checked where the command was asked for AND again after the row is
    -- claimed, because the flag can change while a row is waiting.
    source       varchar(16) NOT NULL
        CONSTRAINT command_request_source_check
            CHECK (source IN ('DISCORD', 'GAME', 'CONSOLE')),

    -- Who asked, for people to read: a Minecraft name, a Discord tag, or `console`. Free text for
    -- the reason `update_request.requested_by` is - a foreign key would mean a request from an
    -- admin who has not linked could not be recorded at all.
    requested_by varchar(64) NOT NULL,

    -- Who asked, for the target to re-check. Either may be null: the console has neither identity,
    -- and a Discord member who has never linked has no Minecraft account. Both being null is only
    -- legitimate for the console, which the CHECK below pins.
    discord_id   varchar(32),
    mc_uuid      uuid,

    -- The asker's language, so the target renders the answer in it. `discord_user.locale` through
    -- `account_link` - carried on the row rather than looked up again, because the answer has to be
    -- in the language of whoever typed it even if their row changes in between.
    locale       varchar(16) NOT NULL DEFAULT 'en',

    requested    timestamptz NOT NULL DEFAULT now(),

    -- When the asker stops waiting. Absolute, written by the caller.
    expires      timestamptz NOT NULL,

    started      timestamptz,
    finished     timestamptz,

    -- The answer, already rendered in `locale`, verbatim - the asking surface prints it and does
    -- not render it again. That is only sound because the shared bundle carries no markup at all:
    -- MiniMessage and Discord's markdown cannot both live in one string.
    result       text,

    -- The console has no identity to record; anything else that claims to be the console is a bug
    -- in an adapter rather than a user error, and this is where it stops.
    CONSTRAINT command_request_console_is_anonymous
        CHECK (source <> 'CONSOLE' OR (discord_id IS NULL AND mc_uuid IS NULL)),

    -- A slash command in the guild always knows the Discord id - it is the interaction's own user.
    -- A row from DISCORD without one could not be re-authorised by the target, which is the half of
    -- the authorisation that exists because the flag can change in flight.
    CONSTRAINT command_request_discord_knows_who
        CHECK (source <> 'DISCORD' OR discord_id IS NOT NULL),

    -- A settled row has a finish time and an unsettled one does not. An equality rather than two
    -- one-way checks, so neither direction can be forgotten.
    CONSTRAINT command_request_finished_iff_settled
        CHECK ((status IN ('DONE', 'FAILED', 'EXPIRED')) = (finished IS NOT NULL)),

    -- A claimed row has a start time. EXPIRED is the one settled status that never had one, which
    -- is exactly what makes it distinguishable from FAILED afterwards: nobody ever picked it up.
    CONSTRAINT command_request_running_has_started
        CHECK (status <> 'RUNNING' OR started IS NOT NULL),
    CONSTRAINT command_request_expired_never_started
        CHECK (status <> 'EXPIRED' OR started IS NULL),
    CONSTRAINT command_request_finished_after_started
        CHECK (started IS NULL OR finished IS NULL OR finished >= started)
);

-- The claim query is "the oldest pending row for my target that has not expired"; partial, so it
-- stays at the handful of rows actually in flight.
CREATE INDEX command_request_pending
    ON command_request (target, id)
    WHERE status = 'PENDING';
