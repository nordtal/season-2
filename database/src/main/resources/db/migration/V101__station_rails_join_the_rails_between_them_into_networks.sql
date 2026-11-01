-- A station rail and the rail networks it joins. A network is the stations and junctions that rails connect, with
-- the track between each two of them, so a map can draw the exact track later. smp walks the rails again whenever
-- a tracked rail is placed or broken and replaces what it stored for the networks the walk covered.
CREATE TABLE smp_rail_station
(
    id        uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    world     text        NOT NULL,
    x         integer     NOT NULL,
    y         integer     NOT NULL,
    z         integer     NOT NULL,
    -- A station rail is straight and flat, along one of the two axes.
    axis      varchar(16) NOT NULL CONSTRAINT smp_rail_station_axis_check CHECK (axis IN ('NORTH_SOUTH', 'EAST_WEST')),
    -- The Minecraft account that placed it, who may name and break it.
    placed_by uuid        NOT NULL,
    placed    timestamptz NOT NULL DEFAULT now(),
    name      varchar(32),
    named     timestamptz,
    CONSTRAINT smp_rail_station_position_key UNIQUE (world, x, y, z),
    CONSTRAINT smp_rail_station_named_with_name CHECK ((name IS NULL) = (named IS NULL))
);
COMMENT ON TABLE smp_rail_station IS 'Owned by smp: a station rail a player placed, and its name once it has one.';

CREATE TABLE smp_rail_junction
(
    world text    NOT NULL,
    x     integer NOT NULL,
    y     integer NOT NULL,
    z     integer NOT NULL,
    PRIMARY KEY (world, x, y, z)
);
COMMENT ON TABLE smp_rail_junction IS 'Owned by smp: a rail of a network that three or more connected rails lead into.';

CREATE TABLE smp_rail_edge
(
    id     uuid    PRIMARY KEY DEFAULT gen_random_uuid(),
    world  text    NOT NULL,
    -- The two nodes it joins, a station or a junction each, by their position.
    from_x integer NOT NULL,
    from_y integer NOT NULL,
    from_z integer NOT NULL,
    to_x   integer NOT NULL,
    to_y   integer NOT NULL,
    to_z   integer NOT NULL,
    -- The number of steps along the rails from one node to the other.
    length integer NOT NULL CONSTRAINT smp_rail_edge_length_positive CHECK (length > 0)
);
COMMENT ON TABLE smp_rail_edge IS 'Owned by smp: the track between two nodes of a rail network.';
CREATE INDEX smp_rail_edge_world_idx ON smp_rail_edge (world);

CREATE TABLE smp_rail_edge_rail
(
    edge    uuid    NOT NULL CONSTRAINT smp_rail_edge_rail_edge_fkey REFERENCES smp_rail_edge (id) ON DELETE CASCADE,
    -- The place of the rail on the track, counted from the edge's first node.
    ordinal integer NOT NULL CONSTRAINT smp_rail_edge_rail_ordinal_not_negative CHECK (ordinal >= 0),
    x       integer NOT NULL,
    y       integer NOT NULL,
    z       integer NOT NULL,
    PRIMARY KEY (edge, ordinal)
);
COMMENT ON TABLE smp_rail_edge_rail IS 'Owned by smp: one rail between the two nodes of a rail network''s edge, in order.';

GRANT SELECT, INSERT, UPDATE, DELETE ON smp_rail_station, smp_rail_junction, smp_rail_edge, smp_rail_edge_rail
    TO ${role_smp};
