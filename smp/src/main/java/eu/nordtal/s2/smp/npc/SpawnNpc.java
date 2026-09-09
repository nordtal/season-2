package eu.nordtal.s2.smp.npc;

import eu.nordtal.s2.smp.config.SmpSpec;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;

/**
 * The figure in the tavern: click it to open the objective list and hand items in.
 *
 * <p>A vanilla {@link Mannequin}: a player-shaped {@code LivingEntity} with no AI, no despawning
 * and no wandering, so no third-party NPC plugin is needed.
 *
 * <p>A later 3D model replaces how the NPC is <em>drawn</em> and nothing about how it is clicked,
 * which is why the interaction lives in its own listener rather than in here.
 *
 * <p>Spawned non-persistent and removed at disable, so the tavern never accumulates a second one -
 * and any left by a crash are swept at start.
 */
public final class SpawnNpc {

    private final Plugin plugin;
    private final SmpSpec config;
    private UUID spawned;

    public SpawnNpc(final Plugin plugin, final SmpSpec config) {
        this.plugin = plugin;
        this.config = config;
    }

    /** Puts the figure in place, removing any this plugin left behind first. Main thread. */
    public void spawn() {
        final SmpSpec.NpcSpec spec = config.npc();
        final World world = Bukkit.getWorld(spec.world());
        if (world == null) {
            plugin.getLogger().warning("the spawn NPC's world '" + spec.world()
                    + "' does not exist - no figure was placed");
            return;
        }
        remove();
        final Location at = new Location(world, spec.x(), spec.y(), spec.z(), spec.yaw(), 0f);
        // Loaded before the sweep, not after: getNearbyEntitiesByType searches loaded chunks only,
        // so an unloaded chunk means the sweep finds nothing and a second figure is spawned on top
        // of the saved one.
        at.getChunk().load();
        sweep(world);

        final Mannequin figure = world.spawn(at, Mannequin.class, mannequin -> {
            mannequin.setImmovable(true);
            mannequin.setInvulnerable(true);
            mannequin.setSilent(true);
            // Persistent: otherwise Paper discards the figure the moment its chunk unloads and
            // leaves an empty spot until the next restart. sweep() above is what keeps persistence
            // from accumulating duplicates.
            mannequin.setPersistent(true);
            if (spec.name() != null && !spec.name().isBlank()) {
                // The ordinary entity label, DELIBERATELY NOT Mannequin#setDescription: both
                // render, one above the other, so setting both gives the figure two labels.
                mannequin.customName(Component.text(spec.name()));
                mannequin.setCustomNameVisible(true);
            }
            // Mannequin.defaultDescription() is the literal English word "NPC", drawn as a second
            // smaller line, so leaving it alone labels the figure twice in one language.
            mannequin.setDescription(Component.empty());
            applySkin(mannequin, spec.skinName());
        });
        spawned = figure.getUniqueId();
    }

    /**
     * Wears somebody's skin, resolved from Mojang.
     *
     * <p>Deliberately neither fatal nor blocking: a default skin is cosmetic, a server that will
     * not start because Mojang is slow is an outage.
     */
    private void applySkin(final Mannequin mannequin, final String skinName) {
        if (skinName == null || skinName.isBlank()) {
            return;
        }
        try {
            final ResolvableProfile profile = ResolvableProfile.resolvableProfile(
                    Bukkit.createProfile(skinName.trim()));
            mannequin.setProfile(profile);
        } catch (final RuntimeException exception) {
            plugin.getLogger().warning("could not put '" + skinName + "'s skin on the spawn NPC: "
                    + exception.getMessage() + " - it keeps the default one");
        }
    }

    /** Removes any mannequin this plugin left standing near the configured spot. */
    private void sweep(final World world) {
        final SmpSpec.NpcSpec spec = config.npc();
        final Location at = new Location(world, spec.x(), spec.y(), spec.z());
        world.getNearbyEntitiesByType(Mannequin.class, at, 4.0)
                .forEach(org.bukkit.entity.Entity::remove);
    }

    public void remove() {
        if (spawned == null) {
            return;
        }
        final org.bukkit.entity.Entity entity = Bukkit.getEntity(spawned);
        if (entity != null) {
            entity.remove();
        }
        spawned = null;
    }

    /** Whether an entity is this NPC - the listener's one question. */
    public boolean is(final UUID entityId) {
        return spawned != null && spawned.equals(entityId);
    }

    public Optional<UUID> id() {
        return Optional.ofNullable(spawned);
    }
}
