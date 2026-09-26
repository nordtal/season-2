-- The payment seam: the columns that let the bot ask for a bunq call instead of making one.
--
-- Concept §10d: Purchases.confirm() calls bunq
-- synchronously out of a Discord interaction, which is what stops the bunq half from moving to
-- steward-worker. The way out is a row rather than a call - the bot writes what it wants, the
-- worker does it and writes back, and a NOTIFY on nordtal_payment makes the answer feel immediate.
--
-- This migration adds the columns and nothing else writes them yet.

ALTER TABLE payment_request
    -- Set when the bot wants a bunq.me tab for this request, NULL otherwise. The worker's queue is
    -- status = 'OPEN' AND tab_requested IS NOT NULL AND bunq_tab_id IS NULL: a row leaves it by
    -- getting a tab (attachTab) or by failing (failTab, which clears this again). That is why the
    -- queue predicate needs no "and not already failed" clause - a failure is not a pending
    -- request.
    ADD COLUMN tab_requested    timestamptz,

    -- What bunq said when it did not work. Without it "der Link kommt gleich" is a state with no
    -- exit: the user waits on a message that never
    -- changes. Set together with clearing tab_requested, so the pair means "asked, refused".
    ADD COLUMN tab_failed       text,

    -- Set when the tab has to go away at bunq. The bot closes the row (SUPERSEDED / CANCELLED /
    -- EXPIRED) and the worker cancels the tab afterwards; today Purchases.close() does both in one
    -- call and that is the second synchronous bunq call inside the Discord process.
    ADD COLUMN cancel_requested timestamptz,

    -- When the worker actually cancelled it. The exit from the cancel queue: without it
    -- cancel_requested IS NOT NULL AND bunq_tab_id IS NOT NULL matches forever and the worker
    -- cancels the same tab on every pass.
    ADD COLUMN tab_cancelled    timestamptz,

    -- What the worker found, and along which of the three paths. bunq_payment_id and its unique
    -- index are untouched and remain the only thing that prevents a double booking; these two say
    -- how much arrived and which mechanism attributed it, neither of which the id carries.
    ADD COLUMN matched_cents    int,
    ADD COLUMN matched_by       varchar(16);

-- The same shape as payment_request_status_check: the strings are an enum in Java
-- (PaymentMatch), and the database is what makes that true rather than hoped for.
ALTER TABLE payment_request
    ADD CONSTRAINT payment_request_matched_by_check
        CHECK (matched_by IS NULL OR matched_by IN ('TAB', 'REFERENCE', 'MANUAL'));

-- No index on either queue on purpose: payment_request holds one row per purchase anyone has ever
-- started, the existing payment_request_status_idx already narrows to the open ones, and a partial
-- index here would be tuned against a table nobody has measured.
--
-- No constraint tying matched_* to status either, and that is the point of ordering them: the
-- worker writes matched_cents and matched_by while the row is still OPEN and settled is still
-- NULL, so payment_request_settled_iff_paid ((status = 'PAID') = (settled IS NOT NULL)) is never
-- crossed. Booking stays one statement that sets status, settled and nothing else.
