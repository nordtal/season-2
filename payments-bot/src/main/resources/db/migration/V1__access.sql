-- Season 2's contribution table. It starts empty on purpose: season 1's rows live in the old
-- MariaDB database and are deliberately not migrated.
--
-- PostgreSQL dialect. gen_random_uuid() is built in from PostgreSQL 13 onwards; no extension.

CREATE TABLE contribution
(
    -- Generated server side and read back by the insert's RETURNING clause, so the application
    -- never invents an id and never has to round-trip to find out what it got.
    id               uuid          PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Discord snowflake of the member the contribution is credited to. Stored as text because
    -- that is what JDA hands out and what the payment description carries.
    receiver_id      varchar(32)   NOT NULL,

    -- numeric, not float. Season 1 stored money in a float column; that is the mistake this
    -- rewrite fixes. The Java field is still a float (see Contribution), but the stored value is
    -- exact to the cent and stays that way for anything that queries the table directly.
    euro_amount      numeric(10, 2) NOT NULL,

    -- Local wall-clock time, written from LocalDateTime.now(). No time zone, matching the type.
    created          timestamp     NOT NULL,

    -- Countdown in seconds. 0 means the contribution never expires.
    duration_seconds bigint        NOT NULL,

    -- The bunq payment this row came from, or -1 for a synthetic row (/test-con, /manual-con).
    bunq_payment_id  bigint        NOT NULL
);

-- Every role refresh resolves the active contribution per member, once per member per cycle.
CREATE INDEX contribution_receiver_id_idx ON contribution (receiver_id);

-- A real bunq payment must never be booked twice. The poll loop already diffs against
-- allProcessedPaymentIds(), but that check is read-then-write and this is the only thing that
-- actually enforces it.
--
-- Partial on purpose: /test-con and /manual-con both write the sentinel -1 and are expected to be
-- run repeatedly, so a plain UNIQUE would break them on the second invocation. Only rows that
-- claim to correspond to an actual bunq payment are constrained.
CREATE UNIQUE INDEX contribution_bunq_payment_id_key
    ON contribution (bunq_payment_id)
    WHERE bunq_payment_id >= 0;
