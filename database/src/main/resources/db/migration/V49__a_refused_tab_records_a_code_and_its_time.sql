-- A refused tab records why as a code and when; the payer's text and the admins' alert are drawn from the code, and
-- bunq's own words go to steward's log only. A refusal kept as text names no code and no time, so it reads as
-- UNKNOWN at the request's creation.
ALTER TABLE payment_request
    ADD COLUMN tab_refusal varchar(32)
        CONSTRAINT payment_request_tab_refusal_check CHECK (tab_refusal IN (
            'NOT_CONFIGURED', 'UNREACHABLE', 'BAD_REQUEST', 'UNAUTHORIZED', 'FORBIDDEN', 'NOT_FOUND',
            'TOO_MANY_REQUESTS', 'BUNQ_ERROR', 'UNKNOWN')),
    ADD COLUMN tab_failed_at timestamptz;

UPDATE payment_request
SET tab_refusal   = 'UNKNOWN',
    tab_failed_at = created
WHERE tab_failed IS NOT NULL;

ALTER TABLE payment_request DROP COLUMN tab_failed;
ALTER TABLE payment_request RENAME COLUMN tab_failed_at TO tab_failed;
ALTER TABLE payment_request
    ADD CONSTRAINT payment_request_tab_failed_with_its_refusal CHECK ((tab_refusal IS NULL) = (tab_failed IS NULL));
