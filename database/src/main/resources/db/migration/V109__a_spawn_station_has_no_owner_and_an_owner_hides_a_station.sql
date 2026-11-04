-- The spawn station stands at a block set in the settings. smp places it and has it without an owner, so its row
-- holds no placed_by. Only the spawn station is without an owner.
ALTER TABLE smp_rail_station
    ALTER COLUMN placed_by DROP NOT NULL;

-- A hidden station is left off the map of the rail networks. It stays in the ride menu.
ALTER TABLE smp_rail_station
    ADD COLUMN hidden boolean NOT NULL DEFAULT false;
