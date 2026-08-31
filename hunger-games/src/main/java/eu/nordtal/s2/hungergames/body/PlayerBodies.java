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
 * The disconnected-body mechanism shared by two moments in docs/hunger-games.md: a player who was
 * ready in the lobby and then disconnected before the countdown finished
 * ("their body is teleported onto its tower at the start and waits there for its owner",
 * docs/hunger-games.md#start), and a player who disconnects mid-game
 * ("the body stays, and it stays vulnerable. It does not vanish, it can be killed, and if the
 * border reaches it, it dies", docs/hunger-games.md#disconnects). Both go through this one class -
 * deliberately one mechanism, not two, per this module's task brief.
 *
 * <h2>What is approximated here, and why</h2>
 * A genuinely offline player has no physical entity in a vanilla server at all - there is no Paper
 * API that keeps a disconnected {@code Player} object interactable, damageable or visible to
 * others. What this class does instead: it spawns an {@link ArmorStand} at the player's location,
 * copies their worn equipment and held items onto it when a live {@link Player} is available to
 * copy from (a mid-session disconnect always has one; a player who was never online this session at
 * all, handled by {@link #spawnBareArmorStand(Location, String)}, does not - their gear only exists
 * as stored player NBT this plugin does not parse), and makes it damageable and killable like any
 * other living entity. This is <b>not</b> the same as a real player being there: it cannot take
 * fall damage or breathe underwater the way a player would, and any damage dealt to it does not
 * reduce a real player health record by itself - {@code hg_member}/{@code hg_event} are what
 * actually track life and death, driven by the marker's damage/death events (see the plugin's death
 * listener, which maps a marker entity back to its owning member through this class). It is the
 * closest faithful approximation achievable with vanilla Paper APIs: a standing, (usually) equipped,
 * killable body at the right place that disappears the moment either the player reconnects
 * (converted back into a real teleport, gear returned) or the marker dies (converted into a death
 * record).
 */
public final class PlayerBodies {

    /** Minecraft UUID -> the marker standing in for that player, if any. */
    private final Map<UUID, UUID> markerByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerByMarker = new ConcurrentHashMap<>();

    /**
     * Spawns a body for a player who has a live {@link Player} object to copy equipment from - the
     * mid-session disconnect case. Call this from the quit listener before the {@code Player}
     * object becomes unusable.
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
     * Spawns a body with no copied equipment, for a participant the start sequence has to place on
     * a tower despite never having seen them online this session - see
     * {@link eu.nordtal.s2.hungergames.game.HungerGamesManager#start} for when this applies. The
     * body is otherwise identical: damageable, killable, and removed the same way.
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
