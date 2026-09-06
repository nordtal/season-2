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
        final Location at = new Location(world, spec.x(), spec.y(), spec.z(), spec.yaw(), 0f);
        // Loaded before the sweep, not after. getNearbyEntitiesByType searches loaded chunks only,
        // and the figure is persistent since 2026-09-06 - so on a restart the chunk it was saved in
        // is usually still on disk, the sweep finds nothing, and this spawns a second one on top of
        // the first (finding 106). Loading it here also makes the spawn itself deterministic.
        at.getChunk().load();
        sweep(world);

        final Mannequin figure = world.spawn(at, Mannequin.class, mannequin -> {
            mannequin.setImmovable(true);
            mannequin.setInvulnerable(true);
            mannequin.setSilent(true);
            // Persistent since 2026-09-06: with false, Paper discards the figure the moment its
            // chunk unloads - which on the local stack happened within minutes of the start (the
            // placeholder coordinates are far from the world spawn) and left an empty spot where
            // the NPC had been until the next restart. sweep() above removes whatever the last
            // start left in the world before this one spawns its own, so persistence costs no
            // duplicates.
            mannequin.setPersistent(true);
            if (spec.name() != null && !spec.name().isBlank()) {
                // The ordinary entity label, and DELIBERATELY NOT Mannequin#setDescription, which
                // is what this used to set and only that.
                //
                // Both render, one above the other - measured on a real 26.2 client on 2026-09-06
                // by renaming the live entity: the custom name is the large top line, the
                // description a smaller line under it. Setting both gave the figure two labels.
                //
                // The custom name is the one kept because it is the one that was watched working.
                // The description was set on the figure standing in the tavern that afternoon -
                // `data get entity` reported description: "Nordtal" - and the client drew nothing
                // at all above it; only a figure spawned fresh after the restart showed the text
                // (finding 127). What exactly the older figure was missing was not established,
                // and an ordinary custom name does not depend on the answer.
                mannequin.customName(Component.text(spec.name()));
                mannequin.setCustomNameVisible(true);
            }
            // Vanilla draws a mannequin's description as a second, smaller line under whatever else
            // is above it, and Mannequin.defaultDescription() is the literal English word "NPC".
            // Leaving it alone therefore labels the figure twice - "Nordtal" over "NPC" - with the
            // second line in one language for every reader. Emptied rather than set to the same
            // text, because two identical lines is not better than one.
            mannequin.setDescription(Component.empty());
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
