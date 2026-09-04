-- One admin command, asked for on a surface whose process cannot carry it out, addressed to the
-- process that can. See docs/architecture.md#commands.
--
-- WHY A SECOND REQUEST TABLE, next to `update_request` (V7). They are the same machinery and they
-- are deliberately not the same table, because they are not the same lifetime. An update request is
-- an operational event that is worth keeping: it says which version the network moved to and when,
-- it is read back weeks later, and its `not_before` is a countdown two other processes watch. A
-- command request is a message in flight. It is written, claimed within a second, answered, and
-- then only interesting for as long as somebody is still looking at the reply it produced.
--
-- Folding one into the other would mean either giving `update_request` a nullable `command` column
-- that is null for every row that matters, or giving this table a `not_before` nothing ever sets.
-- Both are the shape where a constraint stops being able to say anything - which V6's `smp_duel`
-- already demonstrated once, at the cost of a table nothing ever wrote a row to.
--
-- WHY IT TRAVELS AT ALL. `/smp aura` has to run in the JVM that has the SMP world open; `/hg start`
-- releases players from a lobby that exists in one process. The front half of a command - who is
-- asking, may they, in which language - is the same everywhere and lives in `:commands`. The back
-- half has an address, and this table is how a request reaches it: a row, a `pg_notify`, and the
-- owning process listening. A request therefore survives a target that happens to be restarting,
-- which a socket call would not.
--
-- No `interval` anywhere, for the reason V4 and V7 both state: `now() + interval '30 seconds'` is
-- calendar arithmetic in the session's time zone, which the JDBC driver takes from the writing
-- JVM's default. `expires` is written by the caller as an absolute instant instead.
CREATE TABLE command_request
(
    id           bigserial PRIMARY KEY,

    -- Which process runs the effect. The five are `eu.nordtal.s2.commands.Target`, and
    -- TargetSchemaTest holds the enum against this CHECK - the pair that V4 established for
    -- `season_phase.phase` and `SeasonPhase`.
    target       varchar(16) NOT NULL
        CONSTRAINT command_request_target_check
            CHECK (target IN ('SMP', 'HUNGER_GAMES', 'LIMBO', 'PROXY', 'BOT')),

    -- The command's path, joined with spaces and without the leading slash: `smp aura`, `hg start`.
    -- That is `Declaration#path`, which is the command's identity on every surface - the adapters
    -- are not allowed to rename anything, so this is not a third name for the same thing.
    command      varchar(64) NOT NULL,

    -- The arguments, as the line that would have been typed after the path. Empty for a command
    -- that takes none.
    --
    -- WHY A LINE AND NOT JSON. Because the declaration makes it unambiguous, provably: at most one
    -- argument is greedy and `Declaration` refuses one that is not last, and no other kind can
    -- contain a space. So splitting on spaces against the declaration round-trips every command
    -- this network has - which RequestArgumentsTest asserts over every declaration there is, rather
    -- than over examples. The alternative was a JSON column, and `:common` has no JSON parser on
    -- purpose: jackson is what jcore dropped, and gson is a platform library that must never be
    -- shaded into a Paper plugin.
    arguments    text        NOT NULL DEFAULT '',

    -- PENDING -> RUNNING -> DONE | FAILED, or PENDING -> EXPIRED. Nothing goes back.
    --
    -- EXPIRED is written by the ASKING side when it stops waiting, not by a sweeper. There is no
    -- sweeper on purpose: the only process that cares whether an answer ever came is the one still
    -- holding an interaction open for it, and a background job would be a thread per process for a
    -- table that is empty almost all of the time. The target guards the same boundary from its own
    -- end by refusing to claim a row whose `expires` has passed, so a slow target and a giving-up
    -- asker cannot both act on one row.
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
    -- not render it again. That is only sound because a command that can travel names message keys
    -- from `:commands`' own bundle, which carries no markup at all: MiniMessage on Minecraft and
    -- Discord's markdown cannot both live in one string, so the shared bundle has neither.
    -- MessageBundlesTest in `:commands` is what keeps that true.
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

    -- A settled row has a finish time and an unsettled one does not. Written as an equality rather
    -- than two one-way checks so that neither direction can be forgotten: `update_request` has the
    -- same shape and `payment_request` learned it the hard way.
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

-- The claim query is "the oldest pending row for my target that has not expired". A partial index
-- keeps it to the handful of rows actually in flight, however long the history gets - the same
-- shape as `update_request_pending`.
CREATE INDEX command_request_pending
    ON command_request (target, id)
    WHERE status = 'PENDING';
