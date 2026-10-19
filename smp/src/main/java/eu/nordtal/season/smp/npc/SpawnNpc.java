package eu.nordtal.season.smp.npc;

import eu.nordtal.season.displaytags.nametag.NameTagManager;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.smp.config.NpcSpec;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/** The spawn NPC, a {@link Mannequin} that a player clicks to open the objective menu. */
public final class SpawnNpc {

    static final DocumentSpec<DocumentSpec.None> NAME =
            DocumentSpec.game("smp", "npc-name", "Spawn NPC", DocumentSpec.Shape.HOLOGRAM);

    private final Plugin plugin;
    private final NpcSpec config;
    private final NameTagManager nameTags;
    private final Function<UUID, Component> name;
    private @Nullable UUID spawned;

    public SpawnNpc(
            final Plugin plugin,
            final NpcSpec config,
            final NameTagManager nameTags,
            final Identities identities,
            final MessageRenderer renderer) {
        this.plugin = plugin;
        this.config = config;
        this.nameTags = nameTags;
        this.name = name(identities::languageOf, renderer);
    }

    /** Removes the old NPC and spawns a new one at the configured spot. Main thread only. */
    public void spawn() {
        final World world = Bukkit.getWorld(config.world());
        if (world == null) {
            plugin.getLogger()
                    .warning("the spawn NPC's world '" + config.world() + "' does not exist - no figure was placed");
            return;
        }
        remove();
        final Location at = new Location(world, config.x(), config.y(), config.z(), config.yaw(), 0f);
        // getNearbyEntitiesByType searches loaded chunks only.
        at.getChunk().load();
        sweep(world);

        final Mannequin figure = world.spawn(at, Mannequin.class, mannequin -> {
            mannequin.setImmovable(true);
            mannequin.setInvulnerable(true);
            mannequin.setSilent(true);
            // Paper discards a non-persistent entity on chunk unload.
            mannequin.setPersistent(true);
            // Removes the default description, the text "NPC".
            mannequin.setDescription(null);
            applySkin(mannequin, config.skinName());
        });
        spawned = figure.getUniqueId();
        // A mannequin's own name is the same for every viewer, so the name tag draws it in each viewer's language.
        nameTags.createEntityNameTag(figure, name);
    }

    /** Sets the skin of the player named {@code skinName}. It logs a warning if that fails. */
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

    /** Removes every {@link Mannequin} within 4 blocks of the configured spot. */
    private void sweep(final World world) {
        final Location at = new Location(world, config.x(), config.y(), config.z());
        world.getNearbyEntitiesByType(Mannequin.class, at, 4.0).forEach(org.bukkit.entity.Entity::remove);
    }

    /** Renders the NPC's name in the language of each viewer. */
    static Function<UUID, Component> name(final Function<UUID, Locale> languageOf, final MessageRenderer renderer) {
        return viewer -> renderer.format(languageOf.apply(viewer), Sent.of(NAME));
    }

    public void remove() {
        if (spawned == null) {
            return;
        }
        final org.bukkit.entity.Entity entity = Bukkit.getEntity(spawned);
        if (entity != null) {
            nameTags.removeEntityNameTag(entity);
            entity.remove();
        }
        spawned = null;
    }

    public boolean is(final UUID entityId) {
        return spawned != null && spawned.equals(entityId);
    }

    public Optional<UUID> id() {
        return Optional.ofNullable(spawned);
    }
}
