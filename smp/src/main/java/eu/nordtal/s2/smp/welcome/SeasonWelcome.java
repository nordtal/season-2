package eu.nordtal.s2.smp.welcome;

import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.stage.Cinematic;
import eu.nordtal.s2.papercommon.stage.BukkitCinematics;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.farm.LandingSite;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.world.WorldRole;
import eu.nordtal.s2.smp.world.Worlds;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The one moment a player gets on their very first join of the season.
 *
 * <p>Blindness and a short run of pictures in the title slot. {@link Cinematic} and
 * {@link BukkitCinematics} run it; this class only decides when it happens, what is in it, and what
 * makes it happen once.
 *
 * <p>{@link SmpDao#claimWelcome} takes the flag in one statement before anything is shown, so a
 * reconnect, a restart mid-welcome and two racing sessions all end with one moment.
 *
 * <p>Called after the player's language has landed, never from {@code PlayerJoinEvent}:
 * {@code PlayerLocales#of} answers English until the row arrives.
 *
 * <p><b>It is also where a player is first put down.</b> {@code first-join-spawn} in
 * {@code config.yml} is read here and nowhere else, which is the point: it rides the claim above,
 * so the teleport happens on the same single occasion the pictures do and a returning player is
 * never moved. That also means an unlinked player - no {@code smp_player} row to claim against -
 * is left where the server spawned them, exactly as they were before this existed.
 *
 * <p><b>The pictures are a placeholder and are meant to look like one</b> - the finished frames are
 * textures in a font of this project's own, which needs the resource pack, {@code Glyphs} and a
 * font file together. Replacing them is a one-line change to {@link #frames()}.
 */
public final class SeasonWelcome {

    /** How long each picture is on screen. */
    private static final int FRAME_TICKS = 20;

    /**
     * The placeholder sequence.
     *
     * <p>Marked and numbered on purpose: it is supposed to be noticed and reported.
     */
    private static final List<String> PLACEHOLDER_FRAMES = List.of(
            "[ nordtal intro - placeholder 1/3 ]",
            "[ nordtal intro - placeholder 2/3 ]",
            "[ nordtal intro - placeholder 3/3 ]");

    private final Plugin plugin;
    private final SmpDao dao;
    private final Identities identities;
    private final PlayerLocales locales;
    private final BukkitCinematics cinematics;
    private final SmpSpec config;
    private final Worlds worlds;

    public SeasonWelcome(final Plugin plugin, final SmpDao dao, final Identities identities,
                         final PlayerLocales locales,
                         final BukkitCinematics cinematics, final SmpSpec config,
                         final Worlds worlds) {
        this.plugin = plugin;
        this.dao = dao;
        this.identities = identities;
        this.locales = locales;
        this.cinematics = cinematics;
        this.config = config;
        this.worlds = worlds;
    }

    /**
     * Called once per join, on the main thread, after the player's language has landed.
     *
     * <p>Not a {@code PlayerJoinEvent} handler of its own: that would run before the language is
     * known, and no second event fires when it arrives. {@code PresenceListener} owns the callback.
     */
    public void onLanguageReady(final Player player) {
        final UUID uuid = player.getUniqueId();
        // Identities is filled at pre-login by JoinGate, so this is a map read rather than a query.
        // An unlinked player has no smp_player row to claim against, so they get no moment rather
        // than one on every join.
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
     * <p>By UUID rather than the {@code Player} captured at join: the player may have reconnected
     * between the claim committing and this task running, and a captured instance of a reconnected
     * player answers {@code isOnline()} false for ever.
     */
    private void show(final UUID uuid, final String name, final String discordId) {
        final Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            // The one path that loses the moment with the flag already taken. Logged rather than
            // repaired: this is a picture, not a payout.
            plugin.getLogger().info(name + " left in the tick after their own join, so the season's"
                    + " opening moment was claimed but never shown. To give it back:"
                    + " UPDATE smp_player SET welcome_shown = false WHERE discord_id = '"
                    + discordId + "';");
            return;
        }
        // Before the pictures, so the blindness starts where they will be standing when it lifts
        // rather than covering a teleport. This is the only place first-join-spawn is read.
        placeAtFirstJoinSpawn(player, name);
        // Still run from the locale callback even though nothing in the moment is language any
        // more: it is the callback that fires once the player is fully arrived, and a staged moment
        // must not begin while a join is still settling.
        cinematics.start(player, cinematic());
    }

    /**
     * Puts the player down at {@code first-join-spawn}, once.
     *
     * <p><b>{@code safeAt} and not {@code findSafeAt}</b>, which is the opposite of what the
     * balloon does two files away and is deliberate: {@link LandingSite#findSafeAt} is for a caller
     * that is allowed to say no, and its own Javadoc names the balloon as the only one. A first
     * join has to end somewhere. A point nobody fits at therefore falls back to the point as
     * written rather than cancelling the arrival - a player standing in a wall is a bug report, a
     * player who never arrived is a season that did not start.
     *
     * <p>A world name that resolves to nothing is the one case that skips the teleport entirely,
     * leaving the player where the server spawned them - which is what happened on every first join
     * before 2026-09-12, so the fallback is the old behaviour rather than a new failure mode.
     * {@code SmpPlugin} warns about the same name once at enable, so this line is the second
     * warning and not the first.
     */
    private void placeAtFirstJoinSpawn(final Player player, final String name) {
        final SmpSpec.FirstJoinSpawnSpec spawn = config.firstJoinSpawn();
        final World world = Bukkit.getWorld(spawn.world());
        if (world == null) {
            plugin.getLogger().warning("first-join-spawn names the world '" + spawn.world()
                    + "', which does not exist, so " + name + " was left where the server spawned"
                    + " them. The SMP's build world is called '" + worlds.nameOf(WorldRole.NORDTAL)
                    + "' - if that was renamed, first-join-spawn: world has to be renamed with it.");
            return;
        }
        player.teleport(LandingSite.safeAt(world, new Location(world, spawn.x(), spawn.y(),
                spawn.z(), spawn.yaw(), spawn.pitch())));
    }

    /**
     * What the moment is made of.
     *
     * <p><b>The sound is {@link Feedback#STAGING} and it ships blank</b>: the category and its path
     * through {@code sounds.yml} exist, and an empty key is silence until the artwork arrives.
     *
     * <p>There is deliberately no subtitle - the pictures carry the moment on their own.
     */
    private Cinematic cinematic() {
        return Cinematic.builder()
                .frames(frames(), FRAME_TICKS)
                .sound(Feedback.STAGING)
                // Blindness for exactly as long as the pictures run. Removed by the staging when it
                // ends, when the player dies and when they log out - see BukkitCinematics.
                .effect(new Cinematic.Effect("minecraft:blindness", 0))
                .build();
    }

    /**
     * The pictures.
     *
     * <p>Plain text today. When the art exists these become one component per texture, each naming
     * the font it was drawn in - a code point without its font draws whatever another font put
     * there, which is why the frames are components rather than code points.
     */
    private static List<Component> frames() {
        final List<Component> frames = new ArrayList<>(PLACEHOLDER_FRAMES.size());
        for (final String frame : PLACEHOLDER_FRAMES) {
            // Component.text and not MessageRenderer, which is why this file is named in
            // OneMessageFormatTest's allowlist: a frame is a picture rather than a sentence, and
            // the finished ones are private-use code points, which never go into a .properties
            // file - a glyph reaches a bundle as a {parameter} or not at all.
            frames.add(Component.text(frame));
        }
        return frames;
    }
}
