package eu.nordtal.s2.smp.npc;

import eu.nordtal.s2.smp.config.NpcSpec;
import eu.nordtal.s2.smp.config.SmpSpec;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/** The figure in the tavern, a vanilla {@link Mannequin}: click it to open the objective list and hand items in. */
public final class SpawnNpc {

    private final Plugin plugin;
    private final SmpSpec config;
    private @Nullable UUID spawned;

    public SpawnNpc(final Plugin plugin, final SmpSpec config) {
        this.plugin = plugin;
        this.config = config;
    }

    /** Puts the figure in place, removing any this plugin left behind first, on the main thread. */
    public void spawn() {
        final NpcSpec spec = config.npc();
        final World world = Bukkit.getWorld(spec.world());
        if (world == null) {
            plugin.getLogger()
                    .warning("the spawn NPC's world '" + spec.world() + "' does not exist - no figure was placed");
            return;
        }
        remove();
        final Location at = new Location(world, spec.x(), spec.y(), spec.z(), spec.yaw(), 0f);
        // Loaded before the sweep: getNearbyEntitiesByType searches loaded chunks only.
        at.getChunk().load();
        sweep(world);

        final Mannequin figure = world.spawn(at, Mannequin.class, mannequin -> {
            mannequin.setImmovable(true);
            mannequin.setInvulnerable(true);
            mannequin.setSilent(true);
            // Persistent, or Paper discards the figure on chunk unload; sweep() stops it duplicating.
            mannequin.setPersistent(true);
            if (spec.name() != null && !spec.name().isBlank()) {
                // The ordinary entity label, not Mannequin#setDescription.
                mannequin.customName(Component.text(spec.name()));
                mannequin.setCustomNameVisible(true);
            }
            // Mannequin.defaultDescription() is the literal word "NPC" drawn as a second line.
            mannequin.setDescription(null);
            applySkin(mannequin, spec.skinName());
        });
        spawned = figure.getUniqueId();
    }

    /** Wears somebody's skin, resolved from Mojang, never fatal and never blocking. */
    private void applySkin(final Mannequin mannequin, final String skinName) {
        if (skinName == null || skinName.isBlank()) {
            return;
        }
        try {
            final ResolvableProfile profile =
                    ResolvableProfile.resolvableProfile(Bukkit.createProfile(skinName.trim()));
            mannequin.setProfile(profile);
        } catch (final RuntimeException exception) {
            plugin.getLogger()
                    .warning("could not put '" + skinName + "'s skin on the spawn NPC: " + exception.getMessage()
                            + " - it keeps the default one");
        }
    }

    /** Removes any mannequin this plugin left standing near the configured spot. */
    private void sweep(final World world) {
        final NpcSpec spec = config.npc();
        final Location at = new Location(world, spec.x(), spec.y(), spec.z());
        world.getNearbyEntitiesByType(Mannequin.class, at, 4.0).forEach(org.bukkit.entity.Entity::remove);
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

    /** Whether an entity is this NPC. */
    public boolean is(final UUID entityId) {
        return spawned != null && spawned.equals(entityId);
    }

    public Optional<UUID> id() {
        return Optional.ofNullable(spawned);
    }
}
