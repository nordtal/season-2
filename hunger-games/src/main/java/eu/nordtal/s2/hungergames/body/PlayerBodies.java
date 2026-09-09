package eu.nordtal.s2.hungergames.body;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bodies standing in for absent players: one who was ready in the lobby but disconnected before the
 * countdown finished, and one who disconnects mid-game. Both go through this one class.
 *
 * <p>An offline player has no entity in vanilla, so a killable {@link ArmorStand} approximates one.
 * It is not a player: {@code hg_member}/{@code hg_event} track life and death, driven by the
 * marker's damage and death events mapped back to their owner through this class.</p>
 */
public final class PlayerBodies {

    /** Minecraft UUID -> the marker standing in for that player, if any. */
    private final Map<UUID, UUID> markerByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerByMarker = new ConcurrentHashMap<>();

    /**
     * Spawns a body copying the player's equipment. Call from the quit listener while the
     * {@code Player} object is still usable.
     *
     * @param player the player who just disconnected
     * @param at     where to place the body - their last location
     * @return the marker entity's UUID
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

    /**
     * Spawns a body with no equipment, for a participant never seen online this session - their
     * gear only exists as stored NBT this plugin does not parse.
     *
     * @param at          where to place the body
     * @param displayName shown above the marker
     * @return the marker entity's UUID
     */
    public UUID spawnBareArmorStand(final Location at, final String displayName) {
        return baseArmorStand(at, displayName).getUniqueId();
    }

    /**
     * As {@link #spawnBareArmorStand(Location, String)}, but also registers the marker against a
     * known Minecraft UUID so {@link #ownerOf(UUID)}/{@link #hasBody(UUID)} work for it.
     */
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

    /** @return the Minecraft account a marker entity stands in for, if it is one of ours */
    public UUID ownerOf(final UUID markerEntityUuid) {
        return playerByMarker.get(markerEntityUuid);
    }

    /** @return whether this player currently has a body standing in for them */
    public boolean hasBody(final UUID mcUuid) {
        return markerByPlayer.containsKey(mcUuid);
    }

    public UUID markerOf(final UUID mcUuid) {
        return markerByPlayer.get(mcUuid);
    }

    /** Removes the bookkeeping for a marker - call after despawning it, on reconnect or on death. */
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
