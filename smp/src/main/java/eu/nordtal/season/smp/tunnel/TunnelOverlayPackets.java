package eu.nordtal.season.smp.tunnel;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/** Sends per-player packet-based {@code item_display} overlays for the Tunnel Miner start frame and active zones. */
final class TunnelOverlayPackets {

    private static final int SCALE = 12;
    private static final int BRIGHTNESS = 16;
    private static final int VIEW_RANGE = 17;
    private static final int SHADOW_RADIUS = 18;
    private static final int WIDTH = 20;
    private static final int HEIGHT = 21;
    private static final int ITEM = 23;
    private static final int ITEM_DISPLAY_TRANSFORM = 24;

    private static final int FULL_BRIGHT = 15 << 4 | 15 << 20;
    private static final double WALL_OFFSET = 0.52;
    private static final byte DISPLAY_NONE = 0;

    private final Map<String, ItemStack> itemCache = new HashMap<>();

    /** Holds the active packet entities currently shown to one player. */
    static final class Shown {
        private final boolean startMode;
        private final TunnelFrame frame;
        private final int startEntityId;
        private final Map<TunnelZone, Integer> zoneEntityIds;
        private final Map<TunnelZone, String> zoneModels;

        private Shown(final TunnelFrame frame, final int startEntityId) {
            this.startMode = true;
            this.frame = frame;
            this.startEntityId = startEntityId;
            this.zoneEntityIds = Map.of();
            this.zoneModels = Map.of();
        }

        private Shown(
                final TunnelFrame frame,
                final Map<TunnelZone, Integer> zoneEntityIds,
                final Map<TunnelZone, String> zoneModels) {
            this.startMode = false;
            this.frame = frame;
            this.startEntityId = -1;
            this.zoneEntityIds = zoneEntityIds;
            this.zoneModels = zoneModels;
        }

        boolean sameStart(final TunnelFrame candidate) {
            return startMode
                    && frame.world().equals(candidate.world())
                    && frame.origin().equals(candidate.origin())
                    && frame.heading() == candidate.heading();
        }

        boolean sameActive(final TunnelFrame candidate) {
            return !startMode
                    && frame.world().equals(candidate.world())
                    && frame.origin().equals(candidate.origin())
                    && frame.heading() == candidate.heading();
        }

        int[] allIds() {
            if (startMode) {
                return new int[] {startEntityId};
            }
            return zoneEntityIds.values().stream().mapToInt(Integer::intValue).toArray();
        }
    }

    /** Spawns the 5x5 start frame overlay 0.02 blocks in front of the wall face. */
    Shown spawnStart(final UUID viewer, final TunnelFrame frame) {
        final int id = SpigotReflectionUtil.generateEntityId();
        final Vector3d pos = panelCenter(frame, -2, 0, 5, 5);
        spawnDisplay(viewer, id, pos, frame.heading().overlayYaw(), 5f, 5f, "start");
        return new Shown(frame, id);
    }

    /** Spawns all 7 active-face zone overlays 0.02 blocks in front of the wall face. */
    Shown spawnActive(
            final UUID viewer,
            final TunnelFrame frame,
            final @Nullable TunnelZone aimed,
            final Set<TunnelZone> disabled) {
        final Map<TunnelZone, Integer> ids = new EnumMap<>(TunnelZone.class);
        final Map<TunnelZone, String> models = new EnumMap<>(TunnelZone.class);
        for (final TunnelZone zone : TunnelZone.values()) {
            final int id = SpigotReflectionUtil.generateEntityId();
            final Vector3d pos = panelCenter(frame, zone.colMin(), zone.rowMin(), zone.width(), zone.height());
            final String model = modelFor(zone, aimed, disabled);
            spawnDisplay(viewer, id, pos, frame.heading().overlayYaw(), zone.width(), zone.height(), model);
            ids.put(zone, id);
            models.put(zone, model);
        }
        return new Shown(frame, ids, models);
    }

    /** Updates only the zone entities whose disabled or aimed model changed, with no respawn flicker. */
    void updateActive(
            final UUID viewer,
            final Shown shown,
            final @Nullable TunnelZone nextAim,
            final Set<TunnelZone> nextDisabled) {
        if (shown.startMode) {
            return;
        }
        for (final TunnelZone zone : TunnelZone.values()) {
            final String nextModel = modelFor(zone, nextAim, nextDisabled);
            if (nextModel.equals(shown.zoneModels.get(zone))) {
                continue;
            }
            final Integer id = shown.zoneEntityIds.get(zone);
            if (id != null) {
                setItem(viewer, id, nextModel);
                shown.zoneModels.put(zone, nextModel);
            }
        }
    }

    /** Selects the item model key for {@code zone}, keeping disabled zones unhighlighted. */
    static String modelFor(final TunnelZone zone, final @Nullable TunnelZone aimed, final Set<TunnelZone> disabled) {
        if (disabled.contains(zone)) {
            return zone.modelName() + "_disabled";
        }
        return zone == aimed ? zone.modelName() + "_aimed" : zone.modelName();
    }

    /** Removes all overlay entities for {@code viewer}. */
    void remove(final UUID viewer, final Shown shown) {
        final int[] ids = shown.allIds();
        if (ids.length > 0) {
            send(viewer, new WrapperPlayServerDestroyEntities(ids));
        }
    }

    private void spawnDisplay(
            final UUID viewer,
            final int id,
            final Vector3d pos,
            final float yaw,
            final float width,
            final float height,
            final String model) {
        send(
                viewer,
                new WrapperPlayServerSpawnEntity(
                        id,
                        Optional.of(UUID.randomUUID()),
                        EntityTypes.ITEM_DISPLAY,
                        pos,
                        0f,
                        yaw,
                        yaw,
                        0,
                        Optional.of(Vector3d.zero())));
        final float cull = Math.max(width, height) + 1.0f;
        send(
                viewer,
                new WrapperPlayServerEntityMetadata(
                        id,
                        List.of(
                                new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new Vector3f(width, height, 1f)),
                                new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, FULL_BRIGHT),
                                new EntityData<>(VIEW_RANGE, EntityDataTypes.FLOAT, 1.0f),
                                new EntityData<>(SHADOW_RADIUS, EntityDataTypes.FLOAT, 0f),
                                new EntityData<>(WIDTH, EntityDataTypes.FLOAT, cull),
                                new EntityData<>(HEIGHT, EntityDataTypes.FLOAT, cull),
                                new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK, item(model)),
                                new EntityData<>(ITEM_DISPLAY_TRANSFORM, EntityDataTypes.BYTE, DISPLAY_NONE))));
    }

    private void setItem(final UUID viewer, final int id, final String model) {
        send(
                viewer,
                new WrapperPlayServerEntityMetadata(
                        id, List.of(new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK, item(model)))));
    }

    private ItemStack item(final String model) {
        return itemCache.computeIfAbsent(model, key -> {
            final org.bukkit.inventory.ItemStack stack = org.bukkit.inventory.ItemStack.of(Material.PAPER);
            stack.setData(DataComponentTypes.ITEM_MODEL, Key.key("nordtal", "tunnel_miner/" + key));
            return SpigotConversionUtil.fromBukkitItemStack(stack);
        });
    }

    /** World centre of a sub-rectangle on the front wall, offset 0.02 blocks in front of the face. */
    static Vector3d panelCenter(
            final TunnelFrame frame, final int colMin, final int rowMin, final int width, final int height) {
        final double colCenter = colMin + (width - 1) * 0.5;
        final double rowCenter = rowMin + height * 0.5;
        final TunnelHeading heading = frame.heading();
        final double x = frame.origin().x() + 0.5 + colCenter * heading.rightX() - WALL_OFFSET * heading.forwardX();
        final double y = frame.origin().y() + rowCenter;
        final double z = frame.origin().z() + 0.5 + colCenter * heading.rightZ() - WALL_OFFSET * heading.forwardZ();
        return new Vector3d(x, y, z);
    }

    private static void send(final UUID viewer, final PacketWrapper<?> packet) {
        final ProtocolManager protocol = PacketEvents.getAPI().getProtocolManager();
        final Object channel = protocol.getChannel(viewer);
        if (channel != null) {
            protocol.sendPacket(channel, packet);
        }
    }
}
