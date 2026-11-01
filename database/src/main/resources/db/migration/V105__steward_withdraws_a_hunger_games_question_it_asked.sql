-- Steward withdraws a question it asked hunger-games and no longer waits for, such as a settings trial that timed out.
GRANT UPDATE (status, finished, started) ON hunger_games_inbox TO ${role_steward_ui};

COMMENT ON TABLE hunger_games_inbox IS 'Owned by hunger-games, which claims and carries out every row; steward withdraws a question it no longer waits for. steward asks for the start, a player''s held state, the removal of a stack from it or its window for an admin, an admin''s preview of a text or a document and a try of a settings change.';
