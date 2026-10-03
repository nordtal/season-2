package eu.nordtal.s2.smp.welcome;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.papercommon.player.Identities;
import eu.nordtal.s2.smp.config.FirstJoinSpawnSpec;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.stage.BukkitCinematics;
import eu.nordtal.s2.smp.stage.Cinematic;
import eu.nordtal.s2.smp.world.LandingSite;
import eu.nordtal.s2.smp.world.WorldRole;
import eu.nordtal.s2.smp.world.Worlds;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The one moment a player gets on their very first join of the season, and where they are first put down.
 *
 * {@link SmpDao#claimWelcome} takes the flag before anything is shown, so the moment and the teleport happen once.
 */
public final class SeasonWelcome {

    /** How long each picture is on screen. */
    private static final int FRAME_TICKS = 20;

    /** The placeholder sequence, marked and numbered so it is noticed and reported. */
    private static final List<String> PLACEHOLDER_FRAMES = List.of(
            "[ nordtal intro - placeholder 1/3 ]",
            "[ nordtal intro - placeholder 2/3 ]",
            "[ nordtal intro - placeholder 3/3 ]");

    private final Plugin plugin;
    private final SmpDao dao;
    private final Identities identities;
    private final BukkitCinematics cinematics;
    private final SmpSpec config;
    private final Worlds worlds;

    public SeasonWelcome(
            final Plugin plugin,
            final SmpDao dao,
            final Identities identities,
            final BukkitCinematics cinematics,
            final SmpSpec config,
            final Worlds worlds) {
        this.plugin = plugin;
        this.dao = dao;
        this.identities = identities;
        this.cinematics = cinematics;
        this.config = config;
        this.worlds = worlds;
    }

    /** Called once per join, on the main thread, one tick after join once every join handler ran. */
    public void onLanguageReady(final Player player) {
        final UUID uuid = player.getUniqueId();
        // Identities is filled at pre-login by the plugin base, so this is a map read.
        final Optional<DiscordId> discordId = identities.discordIdOf(uuid);
        if (discordId.isEmpty()) {
            return;
        }
        final String name = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!dao.claimWelcome(discordId.get())) {
                // The ordinary answer on every join but the first.
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> show(uuid, name, discordId.get()));
        });
    }

    /** The main-thread half, by UUID because the player may have reconnected since the claim. */
    private void show(final UUID uuid, final String name, final DiscordId discordId) {
        final Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            // The one path that loses the moment with the flag already taken.
            plugin.getLogger()
                    .info(name + " left in the tick after their own join, so the season's"
                            + " opening moment was claimed but never shown. To give it back:"
                            + " UPDATE smp_player SET welcome_shown = false WHERE discord_id = '"
                            + discordId + "';");
            return;
        }
        // Before the pictures, so the blindness starts where the player will stand rather than covering a teleport.
        placeAtFirstJoinSpawn(player, name);
        // Still run from the locale callback: it fires once fully arrived, never mid-join.
        cinematics.start(player, cinematic());
    }

    /** Puts the player at {@code first-join-spawn} via {@code safeAt}, since a first join has to end somewhere. */
    private void placeAtFirstJoinSpawn(final Player player, final String name) {
        final FirstJoinSpawnSpec spawn = config.firstJoinSpawn();
        final World world = Bukkit.getWorld(spawn.world());
        if (world == null) {
            plugin.getLogger()
                    .warning("first-join-spawn names the world '" + spawn.world()
                            + "', which does not exist, so " + name + " was left where the server spawned"
                            + " them. The SMP's build world is called '" + worlds.nameOf(WorldRole.NORDTAL)
                            + "' - if that was renamed, first-join-spawn: world has to be renamed with it.");
            return;
        }
        player.teleport(LandingSite.safeAt(
                world, new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch())));
    }

    /** What the moment is made of; the {@link Feedback#STAGING} sound ships blank. */
    static Cinematic cinematic() {
        return Cinematic.builder()
                .frames(frames(), FRAME_TICKS)
                .sound(Feedback.STAGING)
                // Blindness for exactly as long as the pictures run; the staging removes it on end, death and logout.
                .effect(new Cinematic.Effect("minecraft:blindness", 0))
                .build();
    }

    /** The pictures, as plain text until the art arrives. */
    private static List<Component> frames() {
        final List<Component> frames = new ArrayList<>(PLACEHOLDER_FRAMES.size());
        for (final String frame : PLACEHOLDER_FRAMES) {
            // Component.text, not MessageRenderer.
            frames.add(Component.text(frame));
        }
        return frames;
    }
}
