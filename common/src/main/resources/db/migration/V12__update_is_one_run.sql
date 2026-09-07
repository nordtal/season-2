-- An update is one run: count down, stop, swap, start, and check that everything came back.
--
-- V7 gave the updater three kinds, and the middle one - APPLY - swapped jars into `plugins/` WHILE
-- THE SERVERS WERE RUNNING, with the restart following separately or not at all. That is finding
-- 147, seen on a real deployment on 2026-09-07: the running JVM's jar is replaced underneath it,
-- and every class it has not yet loaded is simply gone. `onDisable` died with
-- `NoClassDefFoundError: Duels$ActiveDuel` because no duel had run in that container's life. What
-- was lost that time was the arena teardown; had a duel been in progress it would have been both
-- fighters' saved inventories, which is finding 122 by another road.
--
-- Decided by the owner on 2026-09-07, after the alternatives were laid out: APPLY is not fixed, it
-- is REMOVED. A button that swaps a jar under a live server is the defect with a label on it, and
-- keeping it "for emergencies" keeps the defect. What replaces it is UPDATE, which is the whole
-- sequence under one confirmation.
ALTER TABLE update_request
    DROP CONSTRAINT update_request_kind_check;

-- Nothing is rewritten. An APPLY row in a deployed database is a historical record of something
-- that really was asked for, and rewriting history to match today's vocabulary would make the one
-- run that caused this change disappear from the table that recorded it. The CHECK constrains what
-- may be written from now on, which is all a CHECK has ever done here.
ALTER TABLE update_request
    ADD CONSTRAINT update_request_kind_check
        CHECK (kind IN ('REPORT', 'APPLY', 'UPDATE', 'RESTART'));


-- `result` is now JSON, and it is written more than once.
--
-- V7 said, in as many words: "a request is never amended; a `result` column is the whole of it."
-- Both halves of that stopped being true on the same day and for the same reason - the run now
-- takes minutes rather than seconds, because it waits for every service it stopped to report
-- healthy again (up to five). Five minutes of an unchanged message is indistinguishable from a run
-- that has hung, so the row carries the report as it goes: stopping, installing, starting,
-- verifying, done.
--
-- The shape is eu.nordtal.s2.common.update.UpdateReport, written and read by
-- UpdateReports (a hand-rolled codec, so that :common gains no dependency for it). It is
-- deliberately NOT a table of its own, and that was the other option on the table:
--
--   * A request is still written once and read back once. Nothing queries "when was Chunky last
--     swapped?", and V11 already settled the same question the same way for commands - a message in
--     flight is not a history.
--   * The alternative costs a second table, a foreign key and a delete path, to hold rows that are
--     always read as a whole and never joined against anything.
--
-- The cost, and it is real: a row in this column is no longer readable in psql at a glance. That is
-- what `docker compose run --rm updater report` is for, and it renders from the same object.
--
-- No column changes here. `text` holds JSON, and an older row holds the plain text an updater
-- before 2026-09-07 wrote - UpdateReports.parse answers "not a report" for those rather than
-- throwing, and every surface falls back to printing them as they are. Nothing is migrated, because
-- nothing reads a finished request twice.
COMMENT ON COLUMN update_request.result IS
    'The run''s report as JSON (eu.nordtal.s2.common.update.UpdateReport), rewritten as the run '
        'moves through its stages. Rows written before 2026-09-07 hold plain text instead and are '
        'still rendered as such. See V12.';
