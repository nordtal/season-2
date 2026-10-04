package eu.nordtal.s2.smp.grave;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/** The graves' rows, keyed by the owner's {@code discord_id}; a JDBI SqlObject, never called from the main thread. */
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

    /** Every grave that still holds something, read at start so the displays can be put back. */
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

    /**
     * Marks a grave emptied, once.
     *
     * The {@code looted IS NULL} guard credits the experience exactly once when two people empty it together.
     */
    @SqlQuery("""
            UPDATE smp_grave
            SET looted = now(), looted_by = :lootedBy
            WHERE id = :id AND looted IS NULL
            RETURNING id
            """)
    Optional<UUID> markGraveLooted(@Bind("id") UUID id, @Bind("lootedBy") @Nullable String lootedBy);

    /**
     * Writes back what is left in a half emptied grave, or a restart would hand the contents out again.
     *
     * {@code looted IS NULL}, so a late close cannot refill a grave somebody else finished.
     */
    @SqlUpdate("UPDATE smp_grave SET contents = :contents WHERE id = :id AND looted IS NULL")
    int updateGraveContents(@Bind("id") UUID id, @Bind("contents") byte[] contents);

    /**
     * Deletes every grave older than {@code hours}, by its own {@code created}, and says which ones went.
     *
     * @param hours how long a grave may stand; positive, since 0 means this is never called
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
