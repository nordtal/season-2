package eu.nordtal.season.smp.rail;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/** Reads and writes the station rails and the rail networks. Blocking, so call it off the main thread only. */
public interface RailDao {

    @SqlQuery("""
            SELECT id, world, x, y, z, axis, placed_by AS placedBy, name, named
            FROM smp_rail_station
            ORDER BY placed, id
            """)
    @RegisterConstructorMapper(StationRow.class)
    List<StationRow> stations();

    /** Deletes a station that a new one replaces at its position, with its favourites. */
    @SqlUpdate("DELETE FROM smp_rail_station WHERE world = :world AND x = :x AND y = :y AND z = :z")
    void deleteStationAt(@Bind("world") String world, @Bind("x") int x, @Bind("y") int y, @Bind("z") int z);

    @SqlUpdate("""
            INSERT INTO smp_rail_station (id, world, x, y, z, axis, placed_by)
            VALUES (:id, :world, :x, :y, :z, :axis, :placedBy)
            """)
    void insertStation(
            @Bind("id") UUID id,
            @Bind("world") String world,
            @Bind("x") int x,
            @Bind("y") int y,
            @Bind("z") int z,
            @Bind("axis") String axis,
            @Bind("placedBy") @Nullable UUID placedBy);

    @SqlUpdate("DELETE FROM smp_rail_station WHERE id = :id")
    void deleteStation(@Bind("id") UUID id);

    /** Renames a station; {@code unseen} holds a notice for its placer until they are told. */
    @SqlUpdate("UPDATE smp_rail_station SET name = :name, named = :named, renamed_unseen = :unseen WHERE id = :id")
    void renameStation(
            @Bind("id") UUID id,
            @Bind("name") String name,
            @Bind("named") Instant named,
            @Bind("unseen") boolean unseen);

    @SqlQuery("SELECT id FROM smp_rail_station WHERE renamed_unseen")
    List<UUID> unseenRenames();

    @SqlUpdate("UPDATE smp_rail_station SET renamed_unseen = false WHERE id = :id")
    void seen(@Bind("id") UUID id);

    @SqlQuery("SELECT id FROM smp_rail_station WHERE hidden")
    List<UUID> hiddenStations();

    /** Hides a station from the map of the networks, or shows it there again. */
    @SqlUpdate("UPDATE smp_rail_station SET hidden = :hidden WHERE id = :id")
    void hide(@Bind("id") UUID id, @Bind("hidden") boolean hidden);

    @SqlQuery("SELECT player, station FROM smp_rail_favourite")
    @RegisterConstructorMapper(FavouriteRow.class)
    List<FavouriteRow> favourites();

    @SqlUpdate("""
            INSERT INTO smp_rail_favourite (player, station) VALUES (:player, :station)
            ON CONFLICT DO NOTHING
            """)
    void insertFavourite(@Bind("player") UUID player, @Bind("station") UUID station);

    @SqlUpdate("DELETE FROM smp_rail_favourite WHERE player = :player AND station = :station")
    void deleteFavourite(@Bind("player") UUID player, @Bind("station") UUID station);

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

    /** Deletes the edges with {@code ids}, with their rails. */
    @SqlUpdate("DELETE FROM smp_rail_edge WHERE id = ANY(:ids)")
    void deleteEdges(@Bind("ids") List<UUID> ids);

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
