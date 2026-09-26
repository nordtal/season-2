-- WHO is in the game right now, next to V22's HOW MANY.
--
-- V22 built online_count and said in as many words what may live in it: "an identifier, not prose
-- … never a container id and never a player name." It is numbers and nothing else, which is why
-- Steward can know that seven people are playing and not which seven. network-control does know -
-- it runs on Velocity, sees every connection and which backend it is on - but nothing wrote it
-- down. This table is that, and nothing more than that.
--
--
-- WHY IT IS A SECOND TABLE AND NOT A COLUMN ON online_count
--
-- online_count is one row per SUBJECT; this is one row per PLAYER. A list does not fit in a column
-- without becoming an array or a json blob that no reader can filter by freshness on its own, and
-- the subject a player is on changes under them while their row does not otherwise move. Two
-- tables, one writer, one tick - see OnlineWriter, which writes both from the same pass over the
-- proxy so the count and the list can never describe two different moments.
--
--
-- NO HISTORY, AND THE ROWS THAT VANISH ARE DELETED
--
-- The same shape V22 chose and for the same reason: an UPSERT per player, keyed by mc_uuid, that
-- the next tick replaces. What is different here is that the set itself shrinks - a player who
-- logs off must LEAVE this table, not sit in it with an ageing timestamp, because a reader that
-- only filtered by age would still show them for the whole staleness window. So every write is
-- "upsert everyone connected, then delete whatever this write did not touch", in one transaction
-- (see OnlineRosterDao#replace). The table is therefore exactly as long as the player list, and
-- empty is its normal state on a quiet night.
--
-- Deleting instead of keeping is also the whole of this table's data protection story, and it is
-- deliberate: nothing here is a record of who played when. That already exists where it belongs -
-- account_link.mc_name caches the last name seen at login (V21), player_playtime counts the hours
-- (V4) - and this table adds no new category of personal data to the deployment, only a live
-- snapshot of it that disappears on disconnect. It is not a log and must never become one: the
-- day somebody wants "who was on last night", that is a new table with a retention policy and a
-- decision by a human, never an ALTER on this one.
--
--
-- "NO LIST" IS NOT "NOBODY", AND THIS TABLE DOES NOT ENCODE THAT EITHER
--
-- Same division of labour as V22. An empty table is a real answer ("nobody is connected") exactly
-- as often as it is a stale one ("network-control stopped writing"), and telling those apart is
-- `updated` held against ServicesApi's cutoff - the one place in the codebase that interprets this
-- column. A row older than that cutoff is treated as no row at all, never as a player with an old
-- name and never as a zero.
CREATE TABLE online_player
(
    -- The Minecraft account, the same identifier account_link, command_request and every other
    -- table in this schema already look a player up by. A uuid column and not text: pgjdbc maps it
    -- to java.util.UUID both ways, which is what the proxy already holds.
    mc_uuid uuid        NOT NULL,

    -- The name last seen on the connection. A cache of an observation, exactly like
    -- account_link.mc_name (V21) - never a key, never unique, and never something to look a player
    -- up by. varchar(16) is Mojang's own limit, the same one V21 used.
    mc_name varchar(16) NOT NULL,

    -- Which backend this player is on right now: a compose service name (smp, hunger-games,
    -- limbo), the same vocabulary online_count.subject uses.
    --
    -- NULL is a real and expected value here: a player connected to the proxy but not (yet) on a
    -- backend - mid-transfer, or between the login and the first server connection - is online,
    -- and the proxy's own count says so. Writing them with a guessed server would make a per-server
    -- list disagree with that server's count for a tick; leaving the column NULL keeps them in the
    -- network's list, out of every server's list, and true in both.
    subject varchar(64),

    -- When network-control last saw this player connected. Not a login time and not a duration:
    -- every tick rewrites it for everyone still there, which is exactly what makes a row that
    -- stops moving mean "the writer stopped", not "the player idled".
    updated timestamptz NOT NULL,

    CONSTRAINT online_player_pkey PRIMARY KEY (mc_uuid),

    -- An empty name is a writer bug, not a value - the same bound online_count.subject keeps for
    -- the same reason.
    CONSTRAINT online_player_name_check CHECK (length(mc_name) BETWEEN 1 AND 16),

    -- Either a service name or nothing at all; an empty string is neither.
    CONSTRAINT online_player_subject_check
        CHECK (subject IS NULL OR length(subject) BETWEEN 1 AND 64)
);

COMMENT ON TABLE online_player IS
    'Who is connected to the network right now - one row per player, replaced on every write and '
        'DELETED when they log off, never a history. Written by network-control every '
        'OnlineDirectory.WRITE_INTERVAL in the same pass that writes online_count; read by '
        'steward-worker for /api/services.';

COMMENT ON COLUMN online_player.mc_uuid IS
    'The Minecraft account, the identifier the rest of this schema already uses. Primary key: a '
        'player is connected once or not at all.';

COMMENT ON COLUMN online_player.mc_name IS
    'The name last seen on this connection - a cache of an observation like account_link.mc_name, '
        'never a key and never unique (Mojang lets a released name be retaken).';

COMMENT ON COLUMN online_player.subject IS
    'The compose service this player is on (smp, hunger-games, limbo), or NULL for a player the '
        'proxy has but no backend does yet - mid-transfer or just past login. NULL means "online, '
        'nowhere named", never "offline" and never a guess.';

COMMENT ON COLUMN online_player.updated IS
    'When network-control last saw this player connected. Every tick rewrites it for everyone, so '
        'a row that stops moving means the writer stopped - see ServicesApi''s staleness cutoff, '
        'the only place that interprets this column.';
