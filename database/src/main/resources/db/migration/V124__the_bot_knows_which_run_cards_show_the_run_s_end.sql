-- Whether discord-bot drew a run's card to the run's end, so a bot that was down while a run ended redraws that card
-- when it starts, however long ago the run ended.
--
-- This only adds. The cards kept so far count as drawn to the end, so the first start does not redraw a season of
-- cards; new cards start as not drawn to the end.

ALTER TABLE bot_run_card ADD COLUMN settled boolean NOT NULL DEFAULT true;
ALTER TABLE bot_run_card ALTER COLUMN settled SET DEFAULT false;
COMMENT ON COLUMN bot_run_card.settled IS 'Whether the card was drawn from the finished run.';
