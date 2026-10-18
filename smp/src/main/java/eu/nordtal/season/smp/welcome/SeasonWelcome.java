package eu.nordtal.season.smp.welcome;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.config.FirstJoinSpawnSpec;
import eu.nordtal.season.smp.config.SmpSpec;
import eu.nordtal.season.smp.stage.BukkitCinematics;
import eu.nordtal.season.smp.stage.Cinematic;
import eu.nordtal.season.smp.world.LandingSite;
import eu.nordtal.season.smp.world.WorldRole;
import eu.nordtal.season.smp.world.Worlds;
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
 * Shows the opening moment on the first join of the season and moves the player to the first-join spawn.
 *
 * {@link WelcomeDao#claimWelcome} takes the flag before anything is shown, so the moment and the teleport happen once.
 */
public final class SeasonWelcome {

    private static final int FRAME_TICKS = 20;

    private static final List<String> PLACEHOLDER_FRAMES = List.of(
            "[ nordtal intro - placeholder 1/3 ]",
            "[ nordtal intro - placeholder 2/3 ]",
            "[ nordtal intro - placeholder 3/3 ]");

    private final Plugin plugin;
    private final WelcomeDao dao;
    private final Identities identities;
    private final BukkitCinematics cinematics;
    private final SmpSpec config;
    private final Worlds worlds;

    public SeasonWelcome(
            final Plugin plugin,
            final WelcomeDao dao,
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

    /** Runs once per join on the main thread, one tick after the join and after every join handler. */
    public void onLanguageReady(final Player player) {
        final UUID uuid = player.getUniqueId();
        final Optional<DiscordId> discordId = identities.discordIdOf(uuid);
        if (discordId.isEmpty()) {
            return;
        }
        final String name = player.getName();
        PaperScheduler.of(plugin).execute(() -> {
            if (!dao.claimWelcome(discordId.get())) {
                return;
            }
            PaperScheduler.of(plugin).onMain(() -> show(uuid, name, discordId.get()));
        });
    }

    /** Shows the moment on the main thread. It takes the UUID, so it finds a player who reconnected. */
    private void show(final UUID uuid, final String name, final DiscordId discordId) {
        final Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            plugin.getLogger()
                    .info(name + " left in the tick after their own join, so the season's"
                            + " opening moment was claimed but never shown. To give it back:"
                            + " UPDATE smp_player SET welcome_shown = false WHERE discord_id = '"
                            + discordId + "';");
            return;
        }
        placeAtFirstJoinSpawn(player, name);
        cinematics.start(player, cinematic());
    }

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

    /** Returns the cinematic. The {@link Feedback#STAGING} sound is blank. */
    static Cinematic cinematic() {
        return Cinematic.builder()
                .frames(frames(), FRAME_TICKS)
                .sound(Feedback.STAGING)
                // The effect lasts as long as the pictures. The staging removes it on end, death and logout.
                .effect(new Cinematic.Effect("minecraft:blindness", 0))
                .build();
    }

    private static List<Component> frames() {
        final List<Component> frames = new ArrayList<>(PLACEHOLDER_FRAMES.size());
        for (final String frame : PLACEHOLDER_FRAMES) {
            frames.add(Component.text(frame));
        }
        return frames;
    }
}
