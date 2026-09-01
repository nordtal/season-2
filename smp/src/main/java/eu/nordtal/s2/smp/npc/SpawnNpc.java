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
 * <h2>It is a vanilla entity, and that was the whole answer</h2>
 * Paper 26.2 ships {@link Mannequin} - a player-shaped entity with a real skin, settable equipment
 * and poses, that is a {@code LivingEntity} rather than a {@code Mob}. No AI, no despawning, no
 * wandering, nothing to be pushed by a boat or struck by lightning. docs/smp.md weighed three
 * options for this - a villager with its AI off, a custom entity, Citizens - and every one of them
 * was worse than something the server already had. Citizens in particular would have been a fourth
 * mandatory third-party dependency after DisplayTags, PacketEvents and Chunky.
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
        sweep(world);

        final Location at = new Location(world, spec.x(), spec.y(), spec.z(), spec.yaw(), 0f);
        final Mannequin figure = world.spawn(at, Mannequin.class, mannequin -> {
            mannequin.setImmovable(true);
            mannequin.setInvulnerable(true);
            mannequin.setSilent(true);
            mannequin.setPersistent(false);
            if (spec.name() != null && !spec.name().isBlank()) {
                mannequin.setDescription(Component.text(spec.name()));
            }
            applySkin(mannequin, spec.skinName());
        });
        spawned = figure.getUniqueId();
    }

    /**
     * Wears somebody's skin, resolved from Mojang.
     *
     * <p>Deliberately not fatal and deliberately not blocking: a figure with the default skin is a
     * cosmetic disappointment, and a server that will not start because Mojang is slow is an outage.
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
