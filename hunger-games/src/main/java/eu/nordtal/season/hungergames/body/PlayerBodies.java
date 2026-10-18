package eu.nordtal.season.hungergames.body;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

/** The armour stands that stand in for absent players. {@link #ownerOf} returns the player of a stand. */
public final class PlayerBodies {

    /** The stand of each player, by Minecraft UUID. */
    private final Map<UUID, UUID> markerByPlayer = new ConcurrentHashMap<>();

    private final Map<UUID, UUID> playerByMarker = new ConcurrentHashMap<>();

    /** Spawns a stand with the equipment of the player and returns the UUID of the stand. */
    public UUID spawn(final Player player, final Location at) {
        final ArmorStand marker = baseArmorStand(at, player.getName());

        final EntityEquipment equipment = marker.getEquipment();
        final PlayerInventory inventory = player.getInventory();
        if (equipment != null) {
            equipment.setHelmet(inventory.getHelmet());
            equipment.setChestplate(inventory.getChestplate());
            equipment.setLeggings(inventory.getLeggings());
            equipment.setBoots(inventory.getBoots());
            equipment.setItem(EquipmentSlot.HAND, inventory.getItemInMainHand());
            equipment.setItem(EquipmentSlot.OFF_HAND, inventory.getItemInOffHand());
        }

        register(player.getUniqueId(), marker.getUniqueId());
        return marker.getUniqueId();
    }

    /** Spawns a stand without equipment and returns its UUID. */
    public UUID spawnBareArmorStand(final Location at, final String displayName) {
        return baseArmorStand(at, displayName).getUniqueId();
    }

    /** Spawns a stand without equipment, registers it for the Minecraft UUID and returns its UUID. */
    public UUID spawnBareArmorStand(final Location at, final String displayName, final UUID mcUuid) {
        final ArmorStand marker = baseArmorStand(at, displayName);
        register(mcUuid, marker.getUniqueId());
        return marker.getUniqueId();
    }

    private ArmorStand baseArmorStand(final Location at, final String displayName) {
        final ArmorStand marker = (ArmorStand) at.getWorld().spawnEntity(at, EntityType.ARMOR_STAND);
        marker.customName(net.kyori.adventure.text.Component.text(displayName));
        marker.setCustomNameVisible(true);
        marker.setInvisible(false);
        marker.setBasePlate(true);
        marker.setArms(true);
        marker.setMarker(false);
        marker.setGravity(true);
        marker.setInvulnerable(false);
        marker.setCanMove(true);
        return marker;
    }

    private void register(final UUID mcUuid, final UUID markerUuid) {
        markerByPlayer.put(mcUuid, markerUuid);
        playerByMarker.put(markerUuid, mcUuid);
    }

    /** Returns the Minecraft UUID of the player that the stand stands in for, or {@code null}. */
    public @Nullable UUID ownerOf(final UUID markerEntityUuid) {
        return playerByMarker.get(markerEntityUuid);
    }

    /** Returns whether a stand stands in for the player. */
    public boolean hasBody(final UUID mcUuid) {
        return markerByPlayer.containsKey(mcUuid);
    }

    public @Nullable UUID markerOf(final UUID mcUuid) {
        return markerByPlayer.get(mcUuid);
    }

    /** Forgets the stand of the player. It does not despawn the stand. */
    public void remove(final UUID mcUuid) {
        final UUID marker = markerByPlayer.remove(mcUuid);
        if (marker != null) {
            playerByMarker.remove(marker);
        }
    }

    public void removeByMarker(final UUID markerEntityUuid) {
        final UUID owner = playerByMarker.remove(markerEntityUuid);
        if (owner != null) {
            markerByPlayer.remove(owner);
        }
    }
}
