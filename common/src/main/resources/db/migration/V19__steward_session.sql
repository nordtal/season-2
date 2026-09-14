-- Steward's signed-in sessions, moved out of the JVM's memory and into this table.
--
-- Until now a session was a Jetty servlet session: a map in the heap of one process, addressed by
-- JSESSIONID. That worked and it had one property nobody had chosen - EVERY RESTART OF
-- steward-ui SIGNED EVERYBODY OUT. `docker restart` is not a rare event for a service that is
-- being built; neither is an image update. And the same restart that ends the session leaves the
-- cookie in the browser, so what an operator sees is not "please sign in" but a Discord round trip
-- they did not ask for, three redirects long, on a phone, to read one number off the start page.
--
-- The second reason is the one that could not have been solved any other way. §10a asks for a
-- security key before a dangerous action, and "was this session's key held within the last five
-- minutes" is a timestamp that has to be READ, SHOWN and RESET BY HAND from outside the process:
-- the recovery path for a lost key is a command on this host. A named column answers that. Jetty's
-- own JDBC session store is on the classpath and would have made sessions durable for no SQL at
-- all, but it stores one serialised Java object in one column, and a timestamp inside a blob is a
-- timestamp nobody can see from psql.
--
--
-- THE ROW IS CREATED BEFORE ANYBODY IS SIGNED IN, AND THAT IS DELIBERATE
--
-- The OAuth state - the one-time value that makes a callback provably belong to the browser that
-- started the sign-in - has to be stored between /auth/login and /auth/callback, which is before
-- there is an account. So a row starts with `discord_id` NULL and `oauth_state` set, and the
-- callback fills the account in and clears the state. One table rather than two, because the
-- alternative is two tables with the same lifetime, the same sweep and the same cookie.
--
-- The consequence is visible and should be: `select count(*) from steward_session where discord_id
-- is null` counts sign-ins that were started and never finished. A handful is somebody closing the
-- tab at Discord. Thousands is somebody hammering /auth/login, and the sweep below is what keeps
-- that from being a disk problem.
--
--
-- WHAT IS NOT HERE
--
-- No sliding expiry. `expires_at` is set once, at sign-in, and never moved: a session lives thirty
-- days from when it began and then ends, whether it was used daily or not. Sliding would mean a
-- session that is used often never ends at all, which is the opposite of a lifetime. Thirty days
-- is only acceptable at all BECAUSE the key stands in front of everything dangerous - that is the
-- trade Till chose on 2026-09-14, and one half of it must not be quietly kept without the other.
CREATE TABLE steward_session
(
    -- The cookie value itself, not a handle to it: 256 bits from SecureRandom, base64url, 43 chars.
    --
    -- It is therefore a secret in a column, and it is here rather than hashed for one honest
    -- reason: whoever can read this table can already read `access_grant` and write
    -- `update_request`, i.e. can already do everything a stolen session could. Hashing would buy
    -- nothing against that attacker and would cost the ability to answer "which session is this"
    -- from psql while debugging a sign-in. If this database ever gains a reader who is not an
    -- admin, this decision is the one to revisit first.
    id            text        NOT NULL,

    -- NULL until Discord has confirmed who this is. See above.
    discord_id    text,

    -- The name as the guild shows it - the nickname if there is one, the username otherwise.
    -- Kept so that the interface can greet somebody without a call to Discord on every page.
    display_name  text,

    -- The role ids this person had AT SIGN-IN, comma-separated.
    --
    -- A snapshot on purpose. Discord is asked once, in the callback; re-asking on every request
    -- would put an outage at discord.com in front of every page of an interface whose whole job is
    -- to be readable when something is broken. The cost is that removing somebody's admin role
    -- does not sign them out - the session has to expire or be deleted, and deleting it is one
    -- statement on this host. Said out loud because it is the kind of thing that is assumed to
    -- work the other way.
    roles         text,

    -- The one-time value handed to Discord and expected back. Cleared the moment it is used, so a
    -- replayed callback finds nothing to match and is refused.
    oauth_state   text,

    -- The double-submit token. Read by the browser through /api/me and sent back in a header on
    -- every write, which is what a cross-site form cannot do.
    csrf          text        NOT NULL,

    created_at    timestamptz NOT NULL,

    -- Set once at sign-in and never moved. A row past it is not a session, whatever it says.
    expires_at    timestamptz NOT NULL,

    CONSTRAINT steward_session_pkey PRIMARY KEY (id),

    -- An id short enough to guess is the whole security of this table, so the floor is checked
    -- here and not only in the code that writes it.
    CONSTRAINT steward_session_id_check CHECK (length(id) BETWEEN 32 AND 128),
    CONSTRAINT steward_session_csrf_check CHECK (length(csrf) BETWEEN 16 AND 128),

    -- A row either has both halves of an identity or neither. A session with a name and no id
    -- would be one the interface greets and cannot authorise.
    CONSTRAINT steward_session_signed_in_check
        CHECK ((discord_id IS NULL) = (display_name IS NULL)),

    CONSTRAINT steward_session_lifetime_check CHECK (expires_at > created_at)
);

COMMENT ON TABLE steward_session IS
    'One signed-in browser. Created at /auth/login with only an OAuth state, completed at '
        '/auth/callback. Survives a restart of steward-ui, which is why it exists. See V19.';

COMMENT ON COLUMN steward_session.id IS
    'The cookie value itself - 256 random bits, base64url. Treat every row as a credential.';

COMMENT ON COLUMN steward_session.discord_id IS
    'NULL means the sign-in was started and never finished. Those rows are ordinary and are swept.';

COMMENT ON COLUMN steward_session.roles IS
    'The roles held AT SIGN-IN, comma-separated. Removing a role in Discord does not end a session; '
        'deleting the row does.';

COMMENT ON COLUMN steward_session.expires_at IS
    'Absolute, set once. There is no sliding window: thirty days from sign-in, used or not.';


-- The sweep reads this, and nothing else does.
--
-- Deleting expired rows is "everything older than now", which names no id at all, so the primary
-- key cannot serve it. Without this index the hourly sweep is a full scan - which is nothing today
-- with a handful of admins, and is exactly the thing that goes unnoticed until the day somebody
-- points a script at /auth/login and leaves half a million unfinished rows behind.
CREATE INDEX steward_session_by_expiry ON steward_session (expires_at);

COMMENT ON INDEX steward_session_by_expiry IS
    'The sweep: delete from steward_session where expires_at < now(). Names no id, so the primary '
        'key is no use to it.';
