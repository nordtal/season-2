-- A session row keeps the SHA-256 of its cookie, so a dump of the table signs nobody in.
--
-- The rows before this keep the cookie itself, which no hash matches: they go, and everyone signs in once more.

DELETE FROM steward_session;

COMMENT ON COLUMN steward_session.id IS 'The SHA-256 of the cookie value, in lower-case hex; the cookie itself is kept nowhere.';
