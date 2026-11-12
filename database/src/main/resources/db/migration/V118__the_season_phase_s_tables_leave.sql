-- The launch date, maintenance and the game mode replaced the season phase, and no release since 0.77.0 reads or
-- writes its row or its journal detail tables, which hold no rows since the entries that used them went.
DROP TABLE journal_phase, journal_launch, journal_smp_start, journal_unanchored, season_phase;

COMMENT ON TABLE journal_entry IS 'Owned by steward, whose journal reads it. Every service that changes access, the season, a setting, a plugin or a server appends an entry and never updates it: what happened, who did it and what it concerns. The values of its action are in the journal_ table of its family.';
