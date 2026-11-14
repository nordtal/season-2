-- An admin's ask for a pack build now is a row that steward moves on and steward-agent watches, so the agent's
-- signal hub, which re-reads on every wake, builds once per ask.
--
-- This only adds.

CREATE TABLE pack_build_ask
(
    -- The one row, which each ask moves on.
    one   boolean     DEFAULT true CONSTRAINT pack_build_ask_pkey PRIMARY KEY CONSTRAINT pack_build_ask_one CHECK (one),
    asked timestamptz NOT NULL
);
COMMENT ON TABLE pack_build_ask IS 'Owned by steward: when an admin last asked steward-agent to build the resource pack now.';

GRANT SELECT, INSERT, UPDATE ON pack_build_ask TO ${role_steward_ui};
