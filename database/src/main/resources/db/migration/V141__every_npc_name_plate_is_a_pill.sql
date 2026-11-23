-- Every name plate is a pill. Nothing reads plate_style any more, and it keeps its default for older writers until a
-- later migration drops it.
UPDATE npc SET plate_style = 'PILL' WHERE plate_style <> 'PILL';
COMMENT ON COLUMN npc.plate_style IS 'Unused: every name plate is a pill.';
