-- Whether this player has already been shown the season's opening moment.
--
-- A separate migration rather than an edit to V6, for the reason V3, V8 and V9 already wrote down:
-- V6 is committed and has been applied, and Flyway validates the checksum of an applied migration.
--
-- Numbered 16 and not 13: V13, V14 and V15 exist on `main` and this branch was cut before them.
-- Two branches claiming one version number is a Flyway failure at the one moment nobody is
-- watching - the deployment's very first migrate - so the gap is deliberate. Flyway allows gaps in
-- the sequence; it does not allow two files claiming the same number.


-- `smp_player.created` is NOT what this asks, and that is worth being exact about, because the
-- column already exists and reads as though it were.
--
-- A row appears in `smp_player` the first time somebody EARNS SOMETHING - aura, a death location,
-- the start event's head start - not the first time they join. So `created` answers "when did this
-- player first do something the SMP records", which is a different question, is answered late for
-- most people and never for somebody who only ever looks around. And it cannot be CLAIMED: two
-- joins in the same second would both read the same timestamp and both decide they were first.
--
-- What has to be true is that the moment happens exactly once per person for the whole season,
-- including across a restart, a reconnect and two sessions racing each other. That is a claim, and
-- a claim is a boolean written under a WHERE - the shape `hg_winner_reward_granted` already uses
-- three columns up, and for the same reason: the flag is taken BEFORE anything is shown, so the
-- loser of a race gets zero rows back and shows nothing.
--
-- Set it back by hand to give somebody the moment again, which is the whole repair path:
--
--   UPDATE smp_player SET welcome_shown = false WHERE discord_id = '...';
--
-- Defaulting to false means every account that already exists gets the moment on their next join.
-- That is the right answer for a season that has not opened, and it is a deliberate one-off for a
-- season that has: being welcomed once, late, is a smaller surprise than never.
ALTER TABLE smp_player
    ADD COLUMN welcome_shown boolean NOT NULL DEFAULT false;
