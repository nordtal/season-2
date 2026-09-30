-- The fifth season phase, and the one date the whole network counts down to.
--
-- A separate migration rather than an edit to V4, for the reason V3 already wrote down: V4 is
-- committed and has been applied to local databases, and Flyway validates the checksum of an
-- applied migration. Rewriting one in place is only safe while nothing anywhere has run it.


-- PRE_LAUNCH: before the network has ever opened.
--
-- It is not MAINTENANCE with a nicer name. Maintenance interrupts a season that is running and
-- holds players in limbo; this phase precedes the season entirely, lets nobody but an admin in,
-- and is the only phase with a date attached. The server browser and the disconnect screens both
-- count down to that date, which is what makes it worth its own value rather than a boolean
-- somewhere: every process that reads the phase already knows what to do with a phase.
ALTER TABLE season_phase
    DROP CONSTRAINT season_phase_phase_check;

ALTER TABLE season_phase
    ADD CONSTRAINT season_phase_phase_check
        CHECK (phase IN ('PRE_LAUNCH', 'PRE_EVENT', 'START_EVENT', 'SMP', 'MAINTENANCE'));


-- When the network opens, as an absolute instant. NULL means "no date announced yet", which is a
-- real state and not a defect: the phase works without it, and every screen that would have shown
-- a countdown simply says the opening has not been dated yet.
--
-- It lives here, on the row that already exists exactly once and that every process already reads,
-- rather than in the proxy's network.yml - because the Discord bot has to announce the same
-- instant (todo.md #9), and two timestamps meaning one event go apart. It is set by hand today:
--
--   UPDATE season_phase SET launch = timestamptz '2026-10-01 18:00+02' WHERE id;
--
-- Nothing switches the phase when it passes. The countdown reaching zero changes what the browser
-- says and nothing else; who may join stays an admin's decision, taken with /phase, and not a
-- value somebody set weeks earlier and forgot.
ALTER TABLE season_phase
    ADD COLUMN launch timestamptz;


-- The season starts before its opening, not at PRE_EVENT. V4 seeded PRE_EVENT because it was the
-- first state that existed; this moves the network to the state that now precedes it. Guarded so
-- that a database somebody has already switched by hand is not dragged backwards - only a row
-- still sitting on V4's seeded value is moved.
UPDATE season_phase
SET phase = 'PRE_LAUNCH'
WHERE id
  AND phase = 'PRE_EVENT';
