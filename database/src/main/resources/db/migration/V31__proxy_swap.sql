-- The proxy swap: where each player stood, and whether the standby still has anybody.
--
-- WHY THE DATABASE AND NOT THE PROXY'S OWN MEMORY. Everything the proxy knows about a connected
-- player lives in that process, and the whole point of this feature is that the process goes away:
-- it is stopped, its jar is replaced, and it comes back with nothing. The WaitingBook - the obvious
-- place, and where the pack station keeps exactly this kind of fact - would be empty on the other
-- side of the restart, which is the one moment it would have to be full.
--
-- TWO TABLES IN ONE MIGRATION, because they are one feature and a deployment with half of it is a
-- swap that either forgets where everybody was or cannot be ended safely.

-- Where one player stood when the live proxy transferred them away, so the live proxy can put them
-- back when they come home.
--
-- VALID ONLY FOR THE LENGTH OF ONE RUN (Till, 2026-09-19), and the schema says so rather than
-- leaving it to a comment in Java: a row is deleted the moment it is read, and a row nobody came
-- back for is swept by age. Neither is tidiness. A seat that outlived its run would move somebody
-- on an ordinary login, days later, to a server the phase does not point at - a bug that looks
-- exactly like broken routing and has nothing to do with routing.
CREATE TABLE proxy_swap_seat
(
    -- The player. One seat at a time: a second transfer for the same player replaces the first,
    -- because there is only one of them and only one place they can be standing.
    player_uuid uuid        NOT NULL PRIMARY KEY,

    -- The backend they were on, as velocity.toml spells it. Text and not a reference to anything:
    -- the server list is velocity.toml's and this table must not become a second copy of it. A
    -- seat naming a backend the proxy no longer has is read, found missing, and ignored - which is
    -- the same thing that happens to a player whose backend went away while they were gone.
    server      text        NOT NULL,

    -- When the seat was written. The sweep's input, and the only thing that bounds this table:
    -- players who never come back - they closed the client during the swap - would otherwise leave
    -- a row each, for ever.
    recorded_at timestamptz NOT NULL
);

-- The sweep's index. Small table, and it would be a sequential scan either way today; it is here
-- because "today" is a network that has never run a swap with players on it, and the shape of the
-- query is not going to change.
CREATE INDEX proxy_swap_seat_recorded_at_idx ON proxy_swap_seat (recorded_at);

-- Whether the standby proxy is holding anybody right now.
--
-- WHO NEEDS IT: steward-worker, which has to stop the standby at the end of a run and must not do
-- it while somebody is parked there (Till, 2026-09-19 - "stoppt erst, wenn er leer ist"). It cannot
-- ask Velocity; it has no Velocity API and no reason to have one.
--
-- WHY NOT online_count, WHICH ALREADY HOLDS PLAYER COUNTS: because that table is the LIVE proxy's
-- answer to "how many players are on the network", read by the dashboard and by the MOTD. Two
-- proxies writing it is two processes overwriting each other, and for most of the standby's life
-- its answer is zero - so the dashboard would flicker between the truth and nothing, and a worker
-- asking "is the standby empty" could be answered by the live proxy's row. The standby writes here
-- and nowhere else; see OnlineWriter, which is silent on a standby for the same reason.
--
-- ONE ROW, EVER. The `only_row` column is the whole of that constraint: a boolean primary key
-- fixed to true. There is one standby proxy; a second row would mean two, and two would mean
-- nobody knows which answer is current.
CREATE TABLE proxy_standby_state
(
    only_row   boolean     NOT NULL PRIMARY KEY DEFAULT true CHECK (only_row),

    -- How many players the standby is holding. Zero is a real answer and the one the worker waits
    -- for; it is not the same as no row at all, which means the standby has never started or has
    -- not written yet.
    players    integer     NOT NULL,

    -- When it last said so. WHAT MAKES THE ZERO TRUSTWORTHY: a standby that was killed mid-swap
    -- leaves its last count standing for ever, and a reader that only looked at `players` would
    -- either wait on a number nobody is updating or act on one that is minutes old. The reader
    -- decides how stale is too stale; this column is what lets it.
    updated_at timestamptz NOT NULL
);
