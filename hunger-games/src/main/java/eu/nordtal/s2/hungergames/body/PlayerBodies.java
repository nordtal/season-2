package eu.nordtal.s2.hungergames.body;

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

/**
 * Killable armour stands standing in for absent players, before or during a game.
 *
 * Their damage and death map back to the owner through this class; {@code WinTracker} tracks life.
 */
public final class PlayerBodies {

    /** Minecraft UUID -> the marker standing in for that player, if any. */
    private final Map<UUID, UUID> markerByPlayer = new ConcurrentHashMap<>();

    private final Map<UUID, UUID> playerByMarker = new ConcurrentHashMap<>();

    /**
     * Spawns a body copying the player's equipment and returns the marker's UUID.
     *
     * Call from the quit listener while the {@code Player} object is still usable.
     */
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

    /** Spawns a bare body for a participant never seen online this session, and returns the marker's UUID. */
    public UUID spawnBareArmorStand(final Location at, final String displayName) {
        return baseArmorStand(at, displayName).getUniqueId();
    }

    /** As {@link #spawnBareArmorStand(Location, String)}, registered against a UUID for {@link #ownerOf(UUID)}. */
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

    /** Returns the Minecraft account a marker entity stands in for, if it is one of ours. */
    public @Nullable UUID ownerOf(final UUID markerEntityUuid) {
        return playerByMarker.get(markerEntityUuid);
    }

    /** Returns whether this player currently has a body standing in for them. */
    public boolean hasBody(final UUID mcUuid) {
        return markerByPlayer.containsKey(mcUuid);
    }

    public @Nullable UUID markerOf(final UUID mcUuid) {
        return markerByPlayer.get(mcUuid);
    }

    /** Removes the bookkeeping for a marker; call after despawning it, on reconnect or on death. */
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
