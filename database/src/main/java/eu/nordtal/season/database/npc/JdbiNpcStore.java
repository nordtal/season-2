package eu.nordtal.season.database.npc;

import com.google.gson.JsonObject;
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
import java.util.Base64;
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
            "npc.id, npc.name_en, npc.name_de, npc.world, npc.x, npc.y, npc.z, npc.yaw, npc.pose, npc.pitch, "
                    + "npc.dialogue::text AS dialogue, npc.skin, npc_skin.model AS skin_model";
    private static final String FROM = " FROM npc LEFT JOIN npc_skin ON npc_skin.id = npc.skin";

    private final Jdbi jdbi;

    JdbiNpcStore(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public List<Npc> npcs() {
        return jdbi.inTransaction(handle -> {
            final Map<UUID, Map<Slot, Equipment>> equipment = equipment(handle, null);
            return handle.createQuery("SELECT " + COLUMNS + FROM + " ORDER BY npc.changed, npc.id")
                    .map((rows, context) -> npc(rows, equipment))
                    .list();
        });
    }

    @Override
    public Optional<Npc> npc(final UUID id) {
        return jdbi.inTransaction(handle -> {
            final Map<UUID, Map<Slot, Equipment>> equipment = equipment(handle, id);
            return handle.createQuery("SELECT " + COLUMNS + FROM + " WHERE npc.id = :id")
                    .bind("id", id)
                    .map((rows, context) -> npc(rows, equipment))
                    .findOne();
        });
    }

    private static Map<UUID, Map<Slot, Equipment>> equipment(final Handle handle, final @Nullable UUID only) {
        final Map<UUID, Map<Slot, Equipment>> all = new HashMap<>();
        final var query = handle.createQuery(
                "SELECT npc, slot, item, data FROM npc_equipment" + (only == null ? "" : " WHERE npc = :id"));
        if (only != null) {
            query.bind("id", only);
        }
        query.map((rows, context) -> {
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
                Json.decode(rows.getString("dialogue"), Dialogue.class),
                rows.getObject("skin", UUID.class),
                NpcSkin.SkinModel.valueOf(
                        rows.getString("skin_model") == null
                                ? NpcSkin.SkinModel.CLASSIC.name()
                                : rows.getString("skin_model")));
    }

    @Override
    public void save(final Npc npc, final Actor actor) {
        jdbi.useTransaction(handle -> {
            lock(handle, npc.id());
            write(handle, npc, actor);
        });
    }

    @Override
    public boolean publish(final Npc npc, final Actor actor, final int read) {
        return jdbi.inTransaction(handle -> {
            lock(handle, npc.id());
            if (count(handle, npc.id()) != read) {
                return false;
            }
            write(handle, npc, actor);
            return true;
        });
    }

    /** Two writes of one NPC queue here, so each sees the versions the other wrote. */
    private static void lock(final Handle handle, final UUID id) {
        handle.createQuery("SELECT pg_advisory_xact_lock(hashtextextended(:id, 0)) IS NULL")
                .bind("id", id.toString())
                .mapTo(Boolean.class)
                .one();
    }

    @Override
    public Map<UUID, UUID> packed() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT npcs FROM pack_build WHERE state = 'ACTIVE'")
                .mapTo(String.class)
                .findOne()
                .map(JdbiNpcStore::skinIds)
                .orElse(Map.of()));
    }

    private static Map<UUID, UUID> skinIds(final String npcs) {
        final Map<UUID, UUID> ids = new HashMap<>();
        final JsonObject map = Json.decode(npcs, JsonObject.class);
        if (map != null) {
            map.entrySet()
                    .forEach(entry -> ids.put(
                            UUID.fromString(entry.getKey()),
                            UUID.fromString(entry.getValue().getAsString())));
        }
        return Map.copyOf(ids);
    }

    @Override
    public int published(final UUID id) {
        return jdbi.withHandle(handle -> count(handle, id));
    }

    @Override
    public Map<UUID, Integer> published() {
        final Map<UUID, Integer> versions = new HashMap<>();
        jdbi.useHandle(handle -> handle.createQuery("SELECT npc, count(*) AS n FROM npc_dialogue_version GROUP BY npc")
                .map((rows, context) -> versions.put(rows.getObject("npc", UUID.class), rows.getInt("n")))
                .list());
        return Map.copyOf(versions);
    }

    private static int count(final Handle handle, final UUID id) {
        return handle.createQuery("SELECT count(*) FROM npc_dialogue_version WHERE npc = :id")
                .bind("id", id)
                .mapTo(Integer.class)
                .one();
    }

    private static void write(final Handle handle, final Npc npc, final Actor actor) {
        final String dialogue = Json.encode(npc.dialogue());
        final String snapshot = Json.encode(npc);
        handle.createUpdate("""
                        INSERT INTO npc (id, name_en, name_de, world, x, y, z, yaw, pose, pitch, dialogue, skin,
                            actor_kind, actor_id, changed)
                        VALUES (:id, :nameEn, :nameDe, :world, :x, :y, :z, :yaw, :pose, :pitch,
                            CAST(:dialogue AS jsonb), :skin, :kind, :actor, now())
                        ON CONFLICT (id) DO UPDATE SET name_en = excluded.name_en, name_de = excluded.name_de,
                            world = excluded.world, x = excluded.x, y = excluded.y, z = excluded.z,
                            yaw = excluded.yaw, pose = excluded.pose, pitch = excluded.pitch,
                            dialogue = excluded.dialogue, skin = excluded.skin, actor_kind = excluded.actor_kind,
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
                .bind("skin", npc.skin())
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
        handle.createUpdate("""
                        INSERT INTO npc_dialogue_version (npc, dialogue, snapshot, actor_kind, actor_id, saved)
                        VALUES (:id, CAST(:dialogue AS jsonb), CAST(:snapshot AS jsonb), :kind, :actor, now())""")
                .bind("id", npc.id())
                .bind("dialogue", dialogue)
                .bind("snapshot", snapshot)
                .bind("kind", actor.kind().name())
                .bind("actor", actor.id())
                .execute();
        signal(handle, npc.id());
    }

    @Override
    public void remove(final UUID id, final Actor actor) {
        jdbi.useTransaction(handle -> {
            lock(handle, id);
            final int removed = handle.createUpdate("DELETE FROM npc WHERE id = :id")
                    .bind("id", id)
                    .execute();
            if (removed > 0) {
                handle.createUpdate("""
                                INSERT INTO npc_dialogue_version (npc, dialogue, actor_kind, actor_id, saved)
                                VALUES (:id, NULL, :kind, :actor, now())""")
                        .bind("id", id)
                        .bind("kind", actor.kind().name())
                        .bind("actor", actor.id())
                        .execute();
            }
            signal(handle, id);
        });
    }

    @Override
    public List<Version> versions(final UUID id) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT id, dialogue::text AS dialogue, snapshot::text AS snapshot, actor_kind, actor_id, saved
                        FROM npc_dialogue_version WHERE npc = :npc ORDER BY id DESC""")
                .bind("npc", id)
                .map((rows, context) -> new Version(
                        rows.getLong("id"),
                        dialogue(rows.getString("dialogue")),
                        snapshot(rows.getString("snapshot")),
                        Actor.of(rows.getString("actor_kind"), rows.getString("actor_id")),
                        rows.getObject("saved", OffsetDateTime.class).toInstant()))
                .list());
    }

    @Override
    public void upload(final NpcSkin skin, final Actor actor) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO npc_skin (id, model, png, actor_kind, actor_id, created)
                        VALUES (:id, :model, :png, :kind, :actor, now())""")
                .bind("id", skin.id())
                .bind("model", skin.model().name())
                .bind("png", Base64.getDecoder().decode(skin.png()))
                .bind("kind", actor.kind().name())
                .bind("actor", actor.id())
                .execute());
    }

    @Override
    public Optional<NpcSkin> skin(final UUID id) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT id, model, png FROM npc_skin WHERE id = :id")
                .bind("id", id)
                .map((rows, context) -> skinOf(rows))
                .findOne());
    }

    @Override
    public Map<UUID, NpcSkin> skins() {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT npc.id AS npc, npc_skin.id, npc_skin.model, npc_skin.png
                        FROM npc JOIN npc_skin ON npc_skin.id = npc.skin ORDER BY npc.id""")
                .map((rows, context) -> Map.entry(rows.getObject("npc", UUID.class), skinOf(rows)))
                .collectToMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static NpcSkin skinOf(final ResultSet rows) throws SQLException {
        return new NpcSkin(
                rows.getObject("id", UUID.class),
                NpcSkin.SkinModel.valueOf(rows.getString("model")),
                Base64.getEncoder().encodeToString(rows.getBytes("png")));
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
                        INSERT INTO npc_skip (npc, player, menu)
                        SELECT :npc, :player, :menu WHERE EXISTS (SELECT 1 FROM npc WHERE id = :npc)
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

    private static @Nullable Dialogue dialogue(final @Nullable String json) {
        return json == null ? null : Json.decode(json, Dialogue.class);
    }

    private static @Nullable Npc snapshot(final @Nullable String json) {
        return json == null ? null : Json.decode(json, Npc.class);
    }

    // Sends the signal in the transaction that wrote the row.
    private static void signal(final Handle handle, final UUID id) {
        handle.createQuery("SELECT pg_notify('nordtal_npc', :id) IS NULL")
                .bind("id", id.toString())
                .mapTo(Boolean.class)
                .one();
    }
}
