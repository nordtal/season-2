-- When paid access starts running, as an absolute instant.
--
-- A separate migration rather than an edit to V8, for the reason V3 and V8 already wrote down:
-- V8 is committed and has been applied to local databases, and Flyway validates the checksum of
-- an applied migration.


-- The second date on the singleton row, and it is NOT `launch`.
--
-- `launch` (V8) is when the network opens - into PRE_EVENT, the hunger games lobby. Access is not
-- required there: LoginGate lets every linked, unbanned member into PRE_EVENT, START_EVENT and
-- MAINTENANCE, and asks for active access in SMP and nowhere else. So the day the network opens
-- and the day bought time starts running are two different days, with a whole event between them,
-- and anchoring a purchase to `launch` would silently spend the hunger games out of somebody's
-- thirty days.
--
-- It has to be a date set in advance rather than the instant the phase actually switches, because
-- `access_grant.valid_from` is computed inside the INSERT, weeks before the switch - see the
-- append rule in AccessDao. A grant is appended and never rewritten, so there is no later pass
-- that could correct a period that started too early.
--
-- NULL means "no date announced yet", and it is deliberately not an error: a purchase made while
-- it is NULL starts at now(), so the shop works before the season is dated (decided 2026-09-03,
-- so that the flow can be tested internally). That case is logged as a WARNING and written to the
-- admin channel every time, because "we are testing" and "somebody forgot to set the date before
-- the season opened" are otherwise the same silence. Set it by hand:
--
--   UPDATE season_phase SET smp_start = timestamptz '2026-10-08 18:00+02' WHERE id;
--
-- Nothing switches the phase when it passes, exactly as for `launch`. It anchors purchases; who
-- may join stays an admin's decision taken with /phase.
ALTER TABLE season_phase
    ADD COLUMN smp_start timestamptz;
