package eu.nordtal.season.smp.rail;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/** Reads and writes the station rails and the rail networks. Blocking, so call it off the main thread only. */
public interface RailDao {

    @SqlQuery("""
            SELECT id, world, x, y, z, axis, placed_by AS placedBy, name, named
            FROM smp_rail_station
            ORDER BY placed, id
            """)
    @RegisterConstructorMapper(StationRow.class)
    List<StationRow> stations();

    @SqlUpdate("""
            INSERT INTO smp_rail_station (id, world, x, y, z, axis, placed_by)
            VALUES (:id, :world, :x, :y, :z, :axis, :placedBy)
            ON CONFLICT (world, x, y, z) DO NOTHING
            """)
    void insertStation(
            @Bind("id") UUID id,
            @Bind("world") String world,
            @Bind("x") int x,
            @Bind("y") int y,
            @Bind("z") int z,
            @Bind("axis") String axis,
            @Bind("placedBy") UUID placedBy);

    @SqlUpdate("DELETE FROM smp_rail_station WHERE id = :id")
    void deleteStation(@Bind("id") UUID id);

    @SqlUpdate("UPDATE smp_rail_station SET name = :name, named = :named WHERE id = :id")
    void renameStation(@Bind("id") UUID id, @Bind("name") String name, @Bind("named") Instant named);

    @SqlQuery("SELECT world, x, y, z FROM smp_rail_junction")
    @RegisterConstructorMapper(NodeRow.class)
    List<NodeRow> junctions();

    @SqlQuery("""
            SELECT id, world, from_x AS fromX, from_y AS fromY, from_z AS fromZ, to_x AS toX, to_y AS toY,
                   to_z AS toZ, length
            FROM smp_rail_edge
            """)
    @RegisterConstructorMapper(EdgeRow.class)
    List<EdgeRow> edges();

    @SqlQuery("SELECT edge, ordinal, x, y, z FROM smp_rail_edge_rail ORDER BY edge, ordinal")
    @RegisterConstructorMapper(EdgeRailRow.class)
    List<EdgeRailRow> edgeRails();

    @SqlBatch("DELETE FROM smp_rail_junction WHERE world = :world AND x = :x AND y = :y AND z = :z")
    void deleteJunctions(
            @Bind("world") String world,
            @Bind("x") List<Integer> x,
            @Bind("y") List<Integer> y,
            @Bind("z") List<Integer> z);

    /** Deletes every edge that leaves a node at the given positions, with its rails. */
    @SqlBatch("""
            DELETE FROM smp_rail_edge
            WHERE world = :world AND from_x = :x AND from_y = :y AND from_z = :z
            """)
    void deleteEdgesFrom(
            @Bind("world") String world,
            @Bind("x") List<Integer> x,
            @Bind("y") List<Integer> y,
            @Bind("z") List<Integer> z);

    @SqlBatch("INSERT INTO smp_rail_junction (world, x, y, z) VALUES (:world, :x, :y, :z) ON CONFLICT DO NOTHING")
    void insertJunctions(
            @Bind("world") String world,
            @Bind("x") List<Integer> x,
            @Bind("y") List<Integer> y,
            @Bind("z") List<Integer> z);

    @SqlUpdate("""
            INSERT INTO smp_rail_edge (id, world, from_x, from_y, from_z, to_x, to_y, to_z, length)
            VALUES (:id, :world, :fromX, :fromY, :fromZ, :toX, :toY, :toZ, :length)
            """)
    void insertEdge(
            @Bind("id") UUID id,
            @Bind("world") String world,
            @Bind("fromX") int fromX,
            @Bind("fromY") int fromY,
            @Bind("fromZ") int fromZ,
            @Bind("toX") int toX,
            @Bind("toY") int toY,
            @Bind("toZ") int toZ,
            @Bind("length") int length);

    @SqlBatch("INSERT INTO smp_rail_edge_rail (edge, ordinal, x, y, z) VALUES (:edge, :ordinal, :x, :y, :z)")
    void insertEdgeRails(
            @Bind("edge") UUID edge,
            @Bind("ordinal") List<Integer> ordinal,
            @Bind("x") List<Integer> x,
            @Bind("y") List<Integer> y,
            @Bind("z") List<Integer> z);
}
