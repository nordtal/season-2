package eu.nordtal.s2.smp.welcome;

import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.stage.Cinematic;
import eu.nordtal.s2.papercommon.stage.BukkitCinematics;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.player.Identities;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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

    public SeasonWelcome(final Plugin plugin, final SmpDao dao, final Identities identities,
                         final PlayerLocales locales,
                         final BukkitCinematics cinematics) {
        this.plugin = plugin;
        this.dao = dao;
        this.identities = identities;
        this.locales = locales;
        this.cinematics = cinematics;
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
        // Still run from the locale callback even though nothing in the moment is language any
        // more: it is the callback that fires once the player is fully arrived, and a staged moment
        // must not begin while a join is still settling.
        cinematics.start(player, cinematic());
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
