package eu.nordtal.season.database.npc;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.npc.Dialogue.Words;
import eu.nordtal.season.database.npc.Npc.Equipment;
import eu.nordtal.season.database.npc.Npc.Place;
import eu.nordtal.season.database.npc.Npc.Pose;
import eu.nordtal.season.database.npc.Npc.Slot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

final class JdbiNpcStore implements NpcStore {

    private static final String COLUMNS =
            "id, name_en, name_de, world, x, y, z, yaw, pose, pitch, dialogue::text AS dialogue";

    private final Jdbi jdbi;

    JdbiNpcStore(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public List<Npc> npcs() {
        return jdbi.inTransaction(handle -> {
            final Map<UUID, Map<Slot, Equipment>> equipment = equipment(handle, null);
            return handle.createQuery("SELECT " + COLUMNS + " FROM npc ORDER BY changed, id")
                    .map((rows, context) -> npc(rows, equipment))
                    .list();
        });
    }

    @Override
    public Optional<Npc> npc(final UUID id) {
        return jdbi.inTransaction(handle -> {
            final Map<UUID, Map<Slot, Equipment>> equipment = equipment(handle, id);
            return handle.createQuery("SELECT " + COLUMNS + " FROM npc WHERE id = :id")
                    .bind("id", id)
                    .map((rows, context) -> npc(rows, equipment))
                    .findOne();
        });
    }

    private static Map<UUID, Map<Slot, Equipment>> equipment(final Handle handle, final @Nullable UUID only) {
        final Map<UUID, Map<Slot, Equipment>> all = new HashMap<>();
        handle.createQuery("SELECT npc, slot, item, data FROM npc_equipment" + (only == null ? "" : " WHERE npc = :id"))
                .bind("id", only)
                .map((rows, context) -> {
                    all.computeIfAbsent(rows.getObject("npc", UUID.class), npc -> new EnumMap<>(Slot.class))
                            .put(
                                    Slot.valueOf(rows.getString("slot")),
                                    new Equipment(rows.getString("item"), rows.getString("data")));
                    return true;
                })
                .list();
        return all;
    }

    private static Npc npc(final ResultSet rows, final Map<UUID, Map<Slot, Equipment>> equipment) throws SQLException {
        final UUID id = rows.getObject("id", UUID.class);
        return new Npc(
                id,
                new Words(rows.getString("name_en"), rows.getString("name_de")),
                new Place(
                        rows.getString("world"),
                        rows.getDouble("x"),
                        rows.getDouble("y"),
                        rows.getDouble("z"),
                        rows.getFloat("yaw")),
                Pose.valueOf(rows.getString("pose")),
                rows.getFloat("pitch"),
                equipment.getOrDefault(id, Map.of()),
                Json.decode(rows.getString("dialogue"), Dialogue.class));
    }

    @Override
    public void save(final Npc npc, final Actor actor) {
        jdbi.useTransaction(handle -> {
            final String dialogue = Json.encode(npc.dialogue());
            final boolean changed = handle.createQuery("""
                            SELECT NOT EXISTS (SELECT 1 FROM npc WHERE id = :id AND dialogue = CAST(:dialogue AS jsonb))""")
                    .bind("id", npc.id())
                    .bind("dialogue", dialogue)
                    .mapTo(Boolean.class)
                    .one();
            handle.createUpdate("""
                            INSERT INTO npc (id, name_en, name_de, world, x, y, z, yaw, pose, pitch, dialogue,
                                actor_kind, actor_id, changed)
                            VALUES (:id, :nameEn, :nameDe, :world, :x, :y, :z, :yaw, :pose, :pitch,
                                CAST(:dialogue AS jsonb), :kind, :actor, now())
                            ON CONFLICT (id) DO UPDATE SET name_en = excluded.name_en, name_de = excluded.name_de,
                                world = excluded.world, x = excluded.x, y = excluded.y, z = excluded.z,
                                yaw = excluded.yaw, pose = excluded.pose, pitch = excluded.pitch,
                                dialogue = excluded.dialogue, actor_kind = excluded.actor_kind,
                                actor_id = excluded.actor_id, changed = excluded.changed""")
                    .bind("id", npc.id())
                    .bind("nameEn", npc.name().en())
                    .bind("nameDe", npc.name().de())
                    .bind("world", npc.place().world())
                    .bind("x", npc.place().x())
                    .bind("y", npc.place().y())
                    .bind("z", npc.place().z())
                    .bind("yaw", npc.place().yaw())
                    .bind("pose", npc.pose().name())
                    .bind("pitch", npc.pitch())
                    .bind("dialogue", dialogue)
                    .bind("kind", actor.kind().name())
                    .bind("actor", actor.id())
                    .execute();
            handle.createUpdate("DELETE FROM npc_equipment WHERE npc = :id")
                    .bind("id", npc.id())
                    .execute();
            npc.equipment()
                    .forEach((slot, item) -> handle.createUpdate("""
                                    INSERT INTO npc_equipment (npc, slot, item, data) VALUES (:id, :slot, :item, :data)""")
                            .bind("id", npc.id())
                            .bind("slot", slot.name())
                            .bind("item", item.item())
                            .bind("data", item.data())
                            .execute());
            if (changed) {
                handle.createUpdate("""
                                INSERT INTO npc_dialogue_version (npc, dialogue, actor_kind, actor_id, saved)
                                VALUES (:id, CAST(:dialogue AS jsonb), :kind, :actor, now())""")
                        .bind("id", npc.id())
                        .bind("dialogue", dialogue)
                        .bind("kind", actor.kind().name())
                        .bind("actor", actor.id())
                        .execute();
            }
            signal(handle, npc.id());
        });
    }

    @Override
    public void remove(final UUID id, final Actor actor) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("DELETE FROM npc WHERE id = :id").bind("id", id).execute();
            signal(handle, id);
        });
    }

    @Override
    public List<Version> versions(final UUID id) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT id, dialogue::text AS dialogue, actor_kind, actor_id, saved FROM npc_dialogue_version
                        WHERE npc = :npc ORDER BY id DESC""")
                .bind("npc", id)
                .map((rows, context) -> new Version(
                        rows.getLong("id"),
                        Json.decode(rows.getString("dialogue"), Dialogue.class),
                        Actor.of(rows.getString("actor_kind"), rows.getString("actor_id")),
                        rows.getObject("saved", OffsetDateTime.class).toInstant()))
                .list());
    }

    @Override
    public Map<UUID, String> skips(final UUID player) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT npc, menu FROM npc_skip WHERE player = :player")
                .bind("player", player)
                .map((rows, context) -> Map.entry(rows.getObject("npc", UUID.class), rows.getString("menu")))
                .collectToMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public void skip(final UUID npc, final UUID player, final String menu) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO npc_skip (npc, player, menu) VALUES (:npc, :player, :menu)
                        ON CONFLICT (npc, player) DO UPDATE SET menu = excluded.menu""")
                .bind("npc", npc)
                .bind("player", player)
                .bind("menu", menu)
                .execute());
    }

    @Override
    public void unskip(final UUID npc, final UUID player) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM npc_skip WHERE npc = :npc AND player = :player")
                .bind("npc", npc)
                .bind("player", player)
                .execute());
    }

    // Sends the signal in the transaction that wrote the row.
    private static void signal(final Handle handle, final UUID id) {
        handle.createQuery("SELECT pg_notify('nordtal_npc', :id) IS NULL")
                .bind("id", id.toString())
                .mapTo(Boolean.class)
                .one();
    }
}
