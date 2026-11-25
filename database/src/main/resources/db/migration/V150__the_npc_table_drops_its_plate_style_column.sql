-- Every name plate is a pill and no release running beside this one reads or writes plate_style.
ALTER TABLE npc
    DROP CONSTRAINT IF EXISTS npc_plate_style_check,
    DROP COLUMN plate_style;
