package eu.nordtal.season.smp.grave;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/** Reads and writes the table {@code smp_grave}. Blocking, so call it off the main thread only. */
public interface GraveDao {

    @SqlUpdate("""
            INSERT INTO smp_grave (owner_id, world, x, y, z, contents, experience)
            VALUES (:ownerId, :world, :x, :y, :z, :contents, :experience)
            """)
    void createGrave(
            @Bind("ownerId") String ownerId,
            @Bind("world") String world,
            @Bind("x") int x,
            @Bind("y") int y,
            @Bind("z") int z,
            @Bind("contents") byte[] contents,
            @Bind("experience") int experience);

    @SqlQuery("""
            SELECT grave.id            AS id,
                   grave.owner_id      AS ownerId,
                   link.mc_uuid        AS ownerUuid,
                   grave.world         AS world,
                   grave.x             AS x,
                   grave.y             AS y,
                   grave.z             AS z,
                   grave.contents      AS contents,
                   grave.experience    AS experience,
                   grave.created       AS created
            FROM smp_grave grave
                     LEFT JOIN account_link link ON link.discord_id = grave.owner_id
            WHERE grave.looted IS NULL
            ORDER BY grave.created
            """)
    @RegisterRowMapper(GraveRowMapper.class)
    List<GraveRow> openGraves();

    /** Marks a grave as looted and returns its id. Returns empty if it was already looted. */
    @SqlQuery("""
            UPDATE smp_grave
            SET looted = now(), looted_by = :lootedBy
            WHERE id = :id AND looted IS NULL
            RETURNING id
            """)
    Optional<UUID> markGraveLooted(@Bind("id") UUID id, @Bind("lootedBy") @Nullable String lootedBy);

    /** Replaces the contents of a grave that is not looted. Returns the number of updated rows. */
    @SqlUpdate("UPDATE smp_grave SET contents = :contents WHERE id = :id AND looted IS NULL")
    int updateGraveContents(@Bind("id") UUID id, @Bind("contents") byte[] contents);

    /**
     * Deletes the graves that are not looted and older than {@code hours}, and returns them.
     *
     * @param hours the maximum age in hours, greater than 0
     */
    @SqlQuery("""
            DELETE FROM smp_grave
            WHERE looted IS NULL
              AND created < now() - make_interval(hours => :hours)
            RETURNING id, world, x, y, z
            """)
    @RegisterConstructorMapper(ExpiredGrave.class)
    List<ExpiredGrave> expireGravesOlderThan(@Bind("hours") int hours);
}
