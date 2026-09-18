-- payment_notice gets an outbox column, because the process that FINDS the payment and the process
-- that CAN SAY SO are no longer the same one (steward/109).
--
-- Until now the two were: PaymentProcessor inserted the row and, when the insert was the first one,
-- posted the sentence into the admin channel in the next statement. `noticeOnce` returning 1 was
-- both "record this" and "you are the one who tells somebody". After the move steward-worker finds
-- the money - it talks to bunq - and has no Discord connection at all, so the insert and the post
-- are two processes and a column apart.
--
-- It also closes a hole that was always there and never had a name: a bot that died between the
-- INSERT and the channel post lost the alert forever, because the row already existed and
-- `noticeOnce` would answer 0 on every later poll. An unmatchable payment is somebody's money, so
-- "we wrote it down and nobody was ever told" is the worst of the three possible outcomes. The
-- claim below is idempotent in the other direction: posted IS NULL is the queue, and setting it is
-- what leaves it.
ALTER TABLE payment_notice
    -- When the admin channel was told. NULL means nobody has been.
    --
    -- Every row that exists when this migration runs is backfilled to `reported`, NOT to NULL: the
    -- bot of the previous version posted each of them as it inserted it, so the alerts have been
    -- seen. Leaving them NULL would repost the entire history of unmatched payments into the admin
    -- channel on the first start after the update, which is exactly the "a season of history posted
    -- into the channel" failure UpdateFeed.start() exists to avoid.
    ADD COLUMN posted timestamptz;

UPDATE payment_notice SET posted = reported WHERE posted IS NULL;

-- The queue is small and short-lived - a notice is posted within a poll of being written - so this
-- is a partial index on the rows that are actually scanned rather than one over the whole table.
-- It is also the one query the bot runs on every nordtal_payment signal, which is a signal every
-- write of the seam sends, so it runs far more often than a notice is created.
CREATE INDEX payment_notice_unposted_idx ON payment_notice (reported) WHERE posted IS NULL;
