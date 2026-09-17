-- How many players are on each Minecraft-facing service, right now (steward/86).
--
-- steward/81 wants a player count next to smp, hunger-games and limbo on Steward's service list,
-- plus the network's total next to network-control - and the number does not exist anywhere today.
-- A Docker daemon has no idea: a player is a fact inside a JVM's memory, not a fact about a
-- container. The one process that already knows all four without adding anything up is the proxy -
-- network-control runs on Velocity and sees every connection and which backend it is on, which is
-- also where NetworkPing already gets the numbers it puts in the MOTD (see
-- eu.nordtal.s2.networkcontrol.ping.Placeholders). This table is that same knowledge, written down
-- where steward-worker - a process with no Velocity API and no reason to have one - can read it.
--
--
-- ONE ROW PER SUBJECT, OVERWRITTEN. THIS IS NOT metric_sample.
--
-- V17's table is a deliberate time series with a retention policy because steward-ui draws curves
-- from it. Nothing here draws a curve - steward/86 is explicit that a history was not asked for and
-- would be "a data store nobody asked for" if built anyway. So this is the other shape: a single
-- row per subject that the next write replaces, the same pattern player_playtime already uses
-- (V4__phase_admin_playtime.sql) for exactly the same reason - an UPSERT every few seconds, forever,
-- must never grow the table it writes to. Four rows, permanently.
--
--
-- WHY A TABLE AND NOT network-control ANSWERING A REQUEST DIRECTLY
--
-- The alternative is steward-worker asking network-control over the network on every
-- /api/services request. That was rejected: it would make the service table's own availability
-- depend on a second process answering in time (network-control is itself one of the rows the
-- table describes, so asking it about a restart it is itself in the middle of is the one moment the
-- answer is most likely to be missing) and it would need network-control to run a second server
-- with its own port and its own auth, for four integers steward-worker already has a database
-- connection next to. A table steward-worker polls costs nothing new on its side and degrades the
-- way the rest of the dashboard already degrades: a stale or absent row, not a failed request.
--
--
-- THE WRITE INTERVAL IS A CONSTANT, NOT A SETTING - eu.nordtal.s2.common.online.OnlineDirectory
-- .WRITE_INTERVAL, ten seconds, the same figure network.yml#snapshot-refresh-seconds already
-- defaults to for the MOTD numbers this reuses. Four single-column UPSERTs every ten seconds is not
-- a number worth exposing as a knob - MetricDirectory.SAMPLE_INTERVAL sets the same precedent for
-- the same reason: it is the number every cost estimate here is built on, and a deployment that
-- quietly changed it would quietly change what "how fresh is the count" means without anyone
-- deciding so on purpose.
--
--
-- "NO NUMBER" IS NOT ZERO, AND THIS TABLE DOES NOT ENCODE THAT ITSELF.
--
-- A service smp/hunger-games/limbo genuinely showing 0 players (nobody connected) is a fact this
-- table states exactly like every other count. What it cannot state on its own is "network-control
-- has not written since before this row's last value was true" - a restarting proxy, or one that has
-- never started, leaves either no row at all or a row whose `updated` stops moving. Telling those
-- two states apart from a trustworthy 0 is steward-worker's job, reading `updated` against its own
-- staleness cutoff (see eu.nordtal.s2.steward.worker.api.ServicesApi) - the same division of labour
-- ImageResult.State.UNKNOWN and health.ts already use: the table (like the registry call behind
-- ImageResult) states what it found, and the reader is the one place that decides how old is too
-- old to trust.
CREATE TABLE online_count
(
    subject text        NOT NULL,
    players integer     NOT NULL,
    updated timestamptz NOT NULL,

    CONSTRAINT online_count_pkey PRIMARY KEY (subject),

    -- An identifier, not prose - the compose service name (smp, hunger-games, limbo) or the literal
    -- "network-control" for the proxy's own total. Bounded the same way metric_sample.subject is,
    -- for the same reason: an empty one is a collector bug, not a value.
    CONSTRAINT online_count_subject_check CHECK (length(subject) BETWEEN 1 AND 64),

    -- Never negative - a count of connections cannot be, and a negative one reaching this table
    -- would be a bug in the writer worth failing loudly for rather than displaying.
    CONSTRAINT online_count_players_check CHECK (players >= 0)
);

COMMENT ON TABLE online_count IS
    'How many players are on smp, hunger-games and limbo, and the network total under '
        '"network-control" - one row per subject, overwritten on every write, never a history. '
        'Written by network-control every OnlineDirectory.WRITE_INTERVAL; read by steward-worker '
        'for /api/services (steward/86, steward/81).';

COMMENT ON COLUMN online_count.subject IS
    'A compose service name (smp, hunger-games, limbo) or "network-control" for the proxy''s own '
        'total. Never a container id and never a player name.';

COMMENT ON COLUMN online_count.players IS
    'How many players network-control counted for this subject at `updated`. Zero is a real, '
        'trustworthy answer ("nobody is connected") - it is the ABSENCE of a row, or a row whose '
        '`updated` has gone stale, that means "unknown" instead. See ServicesApi.';

COMMENT ON COLUMN online_count.updated IS
    'When network-control last wrote this row. The one field that lets a reader tell a live 0 apart '
        'from a network-control that stopped writing a while ago - see ServicesApi''s staleness '
        'cutoff, which is the only place that interprets this column.';
