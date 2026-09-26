-- Web push subscriptions: the browsers that get told about a traffic
-- light change over the phone's lock screen rather than only on the page they might not have open.
--
-- ONE ROW PER BROWSER SUBSCRIPTION, keyed by the endpoint the push service handed out - the same
-- shape `steward_credential` uses for an authenticator, and for the same reason: a `PushSubscription`
-- is not an account, it is one more way to reach one, and there is no accounts table in this schema
-- to hang a foreign key from. `discord_id` is the same column every other steward_* table already
-- carries this identity in.
--
-- WHY THE ENDPOINT IS THE PRIMARY KEY AND NOT A SURROGATE ONE
-- A push service (FCM, Mozilla's, Apple's) hands out one endpoint per subscription and the same
-- browser is handed a fresh one whenever it resubscribes - so the endpoint already IS the identity
-- a re-subscription has to collide with. A surrogate id would need its own uniqueness constraint on
-- this same column to get that for free, which is a second key for the one thing that matters.
CREATE TABLE steward_push_subscription
(
    -- The URL a push is POSTed to. Not a secret by itself - it identifies which browser, never who
    -- is signed into it - but it is still one more thing that answers with 404 or 410 the day it
    -- stops being valid, which is what AlertWatch reads it for.
    endpoint   text        NOT NULL,

    -- Who this subscription belongs to. The same Discord id `steward_session.discord_id` and
    -- `audit_log.actor` carry - see V20's note on why there is no accounts table to point at instead.
    discord_id text        NOT NULL,

    -- The two keys the browser generated for this subscription, base64url as `PushSubscription.
    -- toJSON()` hands them over. Both are needed for every send: `p256dh` is the ECDH public key the
    -- payload is encrypted to, `auth` is the secret that goes into the same derivation. Neither is
    -- this server's own secret - see steward-ui.yml's `web-push` section for the one that is.
    p256dh     text        NOT NULL,
    auth       text        NOT NULL,

    created_at timestamptz NOT NULL,

    -- When a push last reached this endpoint without a 404/410 back. Null until the first send -
    -- subscribing is not the same claim as "this address has ever answered".
    last_sent_at timestamptz,

    CONSTRAINT steward_push_subscription_pkey PRIMARY KEY (endpoint)
);

COMMENT ON TABLE steward_push_subscription IS
    'One browser''s Web Push subscription. A row that gets a 404 or 410 back is deleted by the '
        'sender that saw it, not marked - see AlertWatch. See V26.';

COMMENT ON COLUMN steward_push_subscription.endpoint IS
    'The push service''s own URL for this subscription. Its own primary key, not a secret: it names '
        'a browser, never a person.';

COMMENT ON COLUMN steward_push_subscription.p256dh IS
    'The subscription''s ECDH public key, base64url, exactly as the browser reported it.';

COMMENT ON COLUMN steward_push_subscription.auth IS
    'The subscription''s auth secret, base64url, exactly as the browser reported it.';

-- Every account-wide question this table is asked: which of this account's browsers are subscribed,
-- asked once per settings page and once whenever the account's own subscription list has to be
-- shown or torn down - not the hot path AlertWatch is on, which reads every row regardless of whose
-- it is.
CREATE INDEX steward_push_subscription_by_account ON steward_push_subscription (discord_id);

COMMENT ON INDEX steward_push_subscription_by_account IS
    'select ... from steward_push_subscription where discord_id = ? - the settings page''s own list.';
