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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;

/** Sends the panels to one player each, as item displays that exist only in that player's client. */
final class WallPackets {

    private static final int POSITION_INTERPOLATION = 10;
    private static final int TRANSLATION = 11;
    private static final int SCALE = 12;
    private static final int BRIGHTNESS = 16;
    private static final int SHADOW_RADIUS = 18;
    private static final int WIDTH = 20;
    private static final int HEIGHT = 21;
    private static final int ITEM = 23;

    /** Full sky and block light, so the wall looks the same by day, by night and underground. */
    private static final int FULL_BRIGHT = 15 << 4 | 15 << 20;

    /** The ticks a panel takes to follow the player up or down. */
    private static final int FOLLOW_TICKS = 3;

    private final Map<String, ItemStack> items = new HashMap<>();

    int newId() {
        return SpigotReflectionUtil.generateEntityId();
    }

    void spawn(final UUID viewer, final int id, final Panel panel, final double base, final String model) {
        send(
                viewer,
                new WrapperPlayServerSpawnEntity(
                        id,
                        Optional.of(UUID.randomUUID()),
                        EntityTypes.ITEM_DISPLAY,
                        new Vector3d(panel.x(), base, panel.z()),
                        0f,
                        panel.yaw(),
                        panel.yaw(),
                        0,
                        Optional.of(Vector3d.zero())));
        send(
                viewer,
                new WrapperPlayServerEntityMetadata(
                        id,
                        List.of(
                                new EntityData<>(POSITION_INTERPOLATION, EntityDataTypes.INT, FOLLOW_TICKS),
                                // The model is one block, centred on the entity: it stands from the base up.
                                new EntityData<>(
                                        TRANSLATION, EntityDataTypes.VECTOR3F, new Vector3f(0, Band.HEIGHT / 2, 0)),
                                new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new Vector3f(1, Band.HEIGHT, 1)),
                                new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, FULL_BRIGHT),
                                new EntityData<>(SHADOW_RADIUS, EntityDataTypes.FLOAT, 0f),
                                // The box the client culls by, so the panel stays while its foot is out of view.
                                new EntityData<>(WIDTH, EntityDataTypes.FLOAT, 1.5f),
                                new EntityData<>(HEIGHT, EntityDataTypes.FLOAT, Band.HEIGHT),
                                new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK, item(model)))));
    }

    void move(final UUID viewer, final int id, final Panel panel, final double base) {
        send(
                viewer,
                new WrapperPlayServerEntityTeleport(
                        id, new Vector3d(panel.x(), base, panel.z()), panel.yaw(), 0f, false));
    }

    void remodel(final UUID viewer, final int id, final String model) {
        send(
                viewer,
                new WrapperPlayServerEntityMetadata(
                        id, List.of(new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK, item(model)))));
    }

    void remove(final UUID viewer, final int... ids) {
        if (ids.length > 0) {
            send(viewer, new WrapperPlayServerDestroyEntities(ids));
        }
    }

    private ItemStack item(final String model) {
        return items.computeIfAbsent(model, ignored -> {
            final org.bukkit.inventory.ItemStack stack = org.bukkit.inventory.ItemStack.of(Material.PAPER);
            stack.setData(DataComponentTypes.ITEM_MODEL, Key.key("nordtal", model));
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
