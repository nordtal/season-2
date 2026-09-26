package eu.nordtal.s2.smp.welcome;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.stage.Cinematic;
import eu.nordtal.s2.papercommon.stage.BukkitCinematics;
import eu.nordtal.s2.smp.config.FirstJoinSpawnSpec;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.player.Identities;
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
 * The one moment a player gets on their very first join of the season.
 *
 * Blindness and a short run of pictures in the title slot. {@link Cinematic} and {@link BukkitCinematics} run it;
 * this class only decides when it happens, what is in it, and what makes it happen once.
 *
 * {@link SmpDao#claimWelcome} takes the flag in one statement before anything is shown, so a reconnect, a restart
 * mid-welcome and two racing sessions all end with one moment.
 *
 * Called after the player's language has landed, never from {@code PlayerJoinEvent}: {@code PlayerLocales#of}
 * answers English until the row arrives.
 *
 * <b>It is also where a player is first put down.</b> {@code first-join-spawn} in {@code config.yml} is read here
 * and nowhere else, which is the point: it rides the claim above, so the teleport happens on the same single
 * occasion the pictures do and a returning player is never moved. That also means an unlinked player - no
 * {@code smp_player} row to claim against - is left where the server spawned them, exactly as they were before this
 * existed.
 *
 * <b>The pictures are a placeholder and are meant to look like one</b> - the finished frames are textures in a font
 * of this project's own, which needs the resource pack, {@code Glyphs} and a font file together. Replacing them is a
 * one-line change to {@link #frames()}.
 */
public final class SeasonWelcome {

    /** How long each picture is on screen. */
    private static final int FRAME_TICKS = 20;

    /**
     * The placeholder sequence.
     *
     * Marked and numbered on purpose: it is supposed to be noticed and reported.
     */
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

    /**
     * Called once per join, on the main thread, after the player's language has landed.
     *
     * Not a {@code PlayerJoinEvent} handler of its own: that would run before the language is known, and no second
     * event
     * fires when it arrives. {@code PresenceListener} owns the callback.
     */
    public void onLanguageReady(final Player player) {
        final UUID uuid = player.getUniqueId();
        // Identities is filled at pre-login by JoinGate, so this is a map read.
        final Optional<String> discordId = identities.discordIdOf(uuid);
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

    /**
     * The main-thread half.
     *
     * By UUID rather than the {@code Player} captured at join: the player may have reconnected between the claim
     * committing and this task running, and a captured instance of a reconnected player answers {@code isOnline()}
     * false
     * for ever.
     */
    private void show(final UUID uuid, final String name, final String discordId) {
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

    /**
     * Puts the player down at {@code first-join-spawn}, once.
     *
     * <b> {@code safeAt} and not {@code findSafeAt} </b>, which is the opposite of what the balloon does two files away
     * and is deliberate: {@link LandingSite#findSafeAt} is for a caller that is allowed to say no, and its own Javadoc
     * names the balloon as the only one. A first join has to end somewhere. A point nobody fits at therefore falls back
     * to the point as written rather than cancelling the arrival - a player standing in a wall is a bug report, a
     * player who never arrived is a season that did not start.
     *
     * A world name that resolves to nothing is the one case that skips the teleport entirely, leaving the player where
     * the server spawned them - which is what used to happen on every first join, so the fallback is the old
     * behaviour rather than a new failure mode. {@code SmpPlugin} warns about the same name once at enable, so this
     * line is the second warning and not the first.
     */
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

    /**
     * What the moment is made of.
     *
     * <b>The sound is {@link Feedback#STAGING} and it ships blank</b>: the category and its path through
     * {@code sounds.yml} exist, and an empty key is silence until the artwork arrives.
     *
     * There is deliberately no subtitle - the pictures carry the moment on their own.
     */
    private Cinematic cinematic() {
        return Cinematic.builder()
                .frames(frames(), FRAME_TICKS)
                .sound(Feedback.STAGING)
                // Blindness for exactly as long as the pictures run; the staging removes it on end, death and logout.
                .effect(new Cinematic.Effect("minecraft:blindness", 0))
                .build();
    }

    /**
     * The pictures.
     *
     * Plain text today. When the art exists these become one component per texture, each naming the font it was drawn
     * in
     * - a code point without its font draws whatever another font put there, which is why the frames are components
     * rather than code points.
     */
    private static List<Component> frames() {
        final List<Component> frames = new ArrayList<>(PLACEHOLDER_FRAMES.size());
        for (final String frame : PLACEHOLDER_FRAMES) {
            // Component.text, not MessageRenderer.
            frames.add(Component.text(frame));
        }
        return frames;
    }
}
