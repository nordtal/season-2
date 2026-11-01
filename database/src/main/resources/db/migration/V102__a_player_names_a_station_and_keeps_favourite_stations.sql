-- A station's placer is told when a merge of two networks gave the station a new name. The flag holds the notice
-- until the placer is online.
ALTER TABLE smp_rail_station
    ADD COLUMN renamed_unseen boolean NOT NULL DEFAULT false;

-- The stations a player marked as favourites. A favourite goes with its station.
CREATE TABLE smp_rail_favourite
(
    -- The Minecraft account of the player.
    player  uuid NOT NULL,
    station uuid NOT NULL CONSTRAINT smp_rail_favourite_station_fkey REFERENCES smp_rail_station (id) ON DELETE CASCADE,
    PRIMARY KEY (player, station)
);
COMMENT ON TABLE smp_rail_favourite IS 'Owned by smp: a station a player marked as a favourite, listed first when riding.';
CREATE INDEX smp_rail_favourite_station_idx ON smp_rail_favourite (station);

GRANT SELECT, INSERT, UPDATE, DELETE ON smp_rail_favourite TO ${role_smp};
