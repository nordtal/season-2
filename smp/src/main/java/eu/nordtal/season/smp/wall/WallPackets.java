package eu.nordtal.season.smp.wall;

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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.bukkit.Color;
import org.bukkit.Material;

/** Sends the panels to one player each, as item displays that exist only in that player's client. */
final class WallPackets {

    private static final int POSITION_INTERPOLATION = 10;
    private static final int TRANSLATION = 11;
    private static final int SCALE = 12;
    private static final int BRIGHTNESS = 16;
    private static final int VIEW_RANGE = 17;
    private static final int SHADOW_RADIUS = 18;
    private static final int WIDTH = 20;
    private static final int HEIGHT = 21;
    private static final int ITEM = 23;

    /** Full sky and block light, so the wall looks the same by day, by night and underground. */
    private static final int FULL_BRIGHT = 15 << 4 | 15 << 20;

    /** The ticks a pixel panel takes to follow the player up or down. */
    private static final int FOLLOW_TICKS = 3;

    /**
     * How far a field panel is drawn, in steps of 64 blocks from its entity.
     *
     * Far enough for a player high above the wall at the farthest the field reaches.
     */
    private static final float FIELD_VIEW_RANGE = 8;

    private final Map<Band.Seen, ItemStack> items = new HashMap<>();

    int newId() {
        return SpigotReflectionUtil.generateEntityId();
    }

    /** Spawns a pixel panel that stands from {@code base} up. */
    void spawnPixel(final UUID viewer, final int id, final Panel panel, final double base, final Band.Seen seen) {
        spawnEntity(viewer, id, panel, base);
        final float height = Look.PIXEL.height();
        send(
                viewer,
                new WrapperPlayServerEntityMetadata(
                        id,
                        List.of(
                                new EntityData<>(POSITION_INTERPOLATION, EntityDataTypes.INT, FOLLOW_TICKS),
                                // The model is one block, centred on the entity: it stands from the base up.
                                new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, new Vector3f(0, height / 2, 0)),
                                new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new Vector3f(1, height, 1)),
                                new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, FULL_BRIGHT),
                                new EntityData<>(SHADOW_RADIUS, EntityDataTypes.FLOAT, 0f),
                                // The box the client culls by, so the panel stays while its foot is out of view.
                                new EntityData<>(WIDTH, EntityDataTypes.FLOAT, 1.5f),
                                new EntityData<>(HEIGHT, EntityDataTypes.FLOAT, height),
                                new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK, item(seen)))));
    }

    /**
     * Spawns a field panel whose entity stands at {@code y} and whose face covers {@code range}.
     *
     * The entity never moves: a reshape changes only its transformation, which the client applies at once.
     */
    void spawnField(
            final UUID viewer,
            final int id,
            final Panel panel,
            final double y,
            final Band.Range range,
            final Band.Seen seen) {
        spawnEntity(viewer, id, panel, y);
        send(
                viewer,
                new WrapperPlayServerEntityMetadata(
                        id,
                        List.of(
                                new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, translation(y, range)),
                                new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, scale(range)),
                                new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, FULL_BRIGHT),
                                new EntityData<>(VIEW_RANGE, EntityDataTypes.FLOAT, FIELD_VIEW_RANGE),
                                new EntityData<>(SHADOW_RADIUS, EntityDataTypes.FLOAT, 0f),
                                // A box of no height is never culled: the shader decides what shows.
                                new EntityData<>(HEIGHT, EntityDataTypes.FLOAT, 0f),
                                new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK, item(seen)))));
    }

    /** Stretches a field panel whose entity stands at {@code y} over {@code range}. */
    void reshape(final UUID viewer, final int id, final double y, final Band.Range range) {
        send(
                viewer,
                new WrapperPlayServerEntityMetadata(
                        id,
                        List.of(
                                new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, translation(y, range)),
                                new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, scale(range)))));
    }

    private void spawnEntity(final UUID viewer, final int id, final Panel panel, final double y) {
        send(
                viewer,
                new WrapperPlayServerSpawnEntity(
                        id,
                        Optional.of(UUID.randomUUID()),
                        EntityTypes.ITEM_DISPLAY,
                        new Vector3d(panel.x(), y, panel.z()),
                        0f,
                        panel.yaw(),
                        panel.yaw(),
                        0,
                        Optional.of(Vector3d.zero())));
    }

    /** The model is one block, centred on the entity: this moves its middle to the middle of the range. */
    private static Vector3f translation(final double y, final Band.Range range) {
        return new Vector3f(0, (float) ((range.bottom() + range.top()) / 2 - y), 0);
    }

    private static Vector3f scale(final Band.Range range) {
        return new Vector3f(1, (float) (range.top() - range.bottom()), 1);
    }

    void move(final UUID viewer, final int id, final Panel panel, final double base) {
        send(
                viewer,
                new WrapperPlayServerEntityTeleport(
                        id, new Vector3d(panel.x(), base, panel.z()), panel.yaw(), 0f, false));
    }

    void remodel(final UUID viewer, final int id, final Band.Seen seen) {
        send(
                viewer,
                new WrapperPlayServerEntityMetadata(
                        id, List.of(new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK, item(seen)))));
    }

    void remove(final UUID viewer, final int... ids) {
        if (ids.length > 0) {
            send(viewer, new WrapperPlayServerDestroyEntities(ids));
        }
    }

    /** The panel's item: its model, and the ground under it in the tint the shader reads. */
    private ItemStack item(final Band.Seen seen) {
        return items.computeIfAbsent(seen, ignored -> {
            final org.bukkit.inventory.ItemStack stack = org.bukkit.inventory.ItemStack.of(Material.PAPER);
            stack.setData(DataComponentTypes.ITEM_MODEL, Key.key("nordtal", seen.model()));
            if (seen.ground() != Band.UNKNOWN) {
                stack.setData(
                        DataComponentTypes.CUSTOM_MODEL_DATA,
                        CustomModelData.customModelData()
                                .addColor(Color.fromRGB(Band.tint(seen.ground())))
                                .build());
            }
            return SpigotConversionUtil.fromBukkitItemStack(stack);
        });
    }

    private static void send(final UUID viewer, final PacketWrapper<?> packet) {
        final ProtocolManager protocol = PacketEvents.getAPI().getProtocolManager();
        final Object channel = protocol.getChannel(viewer);
        if (channel != null) {
            protocol.sendPacket(channel, packet);
        }
    }
}
