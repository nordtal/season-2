-- The notifications get their own management (steward/98, Till's review of 2026-09-18): which kinds
-- of alert an account wants at all, and a name for each browser that can be told apart from the next.
--
-- TWO CHANGES IN ONE MIGRATION, because they are one feature: a dialog that lists devices and
-- switches types needs both, and a deployment that ran only half of it would draw a device list of
-- blank names or a switch panel with nothing behind it.

-- Which kinds of alert one account wants pushed to its browsers.
--
-- ONE ROW PER ACCOUNT AND TYPE, AND A MISSING ROW IS THE DEFAULT - never "off", and never a row
-- written when an account is created. That matters more than it looks: Steward has no accounts
-- table at all (see V20), so "when the account was created" is not a moment anything here knows.
-- If the preferences had to be materialised at some point in an account's life, the answer an
-- account gets would depend on which release it first signed in under. A missing row meaning "the
-- default for this type" makes the default a property of the code, changeable in one place, and
-- identical for somebody who signed in yesterday and somebody who has never opened the dialog.
--
-- The defaults themselves are NOT in this schema, for the same reason: they are
-- `AlertType.enabledByDefault()` in steward-ui, where the type list lives. A default column here
-- would be a second copy of them, and a row written from it would freeze whichever copy was current
-- on the day it was written.
CREATE TABLE steward_push_preference
(
    -- Who. The same Discord id `steward_session.discord_id`, `steward_credential.discord_id` and
    -- `steward_push_subscription.discord_id` carry - see V20's note on why there is no accounts
    -- table to point at instead.
    discord_id text        NOT NULL,

    -- Which kind of alert, as `AlertType`'s own lowercase name: service, backup, disk, memory,
    -- drift. Text and not an enum type: the list is steward-ui's to change, and an added type must
    -- not be a migration. A row naming a type that no longer exists is ignored by the reader and
    -- costs nothing.
    alert_type text        NOT NULL,

    -- What this account chose. Only ever written by somebody tapping the switch; the absence of a
    -- row is the default, and turning a switch back to its default writes the row rather than
    -- deleting it, so that "I chose this" and "I never looked" stay distinguishable in the data.
    enabled    boolean     NOT NULL,

    updated_at timestamptz NOT NULL,

    CONSTRAINT steward_push_preference_pkey PRIMARY KEY (discord_id, alert_type)
);

COMMENT ON TABLE steward_push_preference IS
    'Which alert types one account wants pushed. A MISSING ROW IS THE TYPE''S OWN DEFAULT, never '
        '"off" - see V30 and AlertType.enabledByDefault().';

COMMENT ON COLUMN steward_push_preference.alert_type IS
    'AlertType''s lowercase name. Text rather than an enum type so that adding a type is a commit '
        'and not a migration.';

-- A name for the browser behind an endpoint.
--
-- AN ENDPOINT IS NOT A NAME. It is a push service's own URL - a hundred-odd characters of
-- fcm.googleapis.com or web.push.apple.com and an opaque id - and a person looking at a list of
-- three of them cannot tell which one is the phone in their pocket. This is what steward-ui derives
-- from the User-Agent of the request that subscribed: "iPhone, Safari", "Linux, Firefox". Nullable,
-- because every row written before this migration has no User-Agent to look back at and inventing
-- one would be worse than the interface saying it does not know.
ALTER TABLE steward_push_subscription
    ADD COLUMN device text;

COMMENT ON COLUMN steward_push_subscription.device IS
    'What to call this browser in a list, derived from the User-Agent when it subscribed. Null for '
        'a row written before V30, and for a request that sent no User-Agent at all.';
