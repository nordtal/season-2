package eu.nordtal.s2.smp.welcome;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The one moment a player gets on their very first join of the season.
 *
 * <h2>What it is</h2>
 * Blindness, a short run of pictures in the title slot and a line under them, and later a sound. The
 * device that runs it is {@link Cinematic} in {@code :common} and {@link BukkitCinematics} in
 * {@code :paper-common}; this class is only the decision of when it happens, what is in it, and what
 * makes it happen once.
 *
 * <h2>Exactly once, and the claim is what makes that true</h2>
 * {@link SmpDao#claimWelcome} takes the flag in one statement before anything is shown, so a
 * reconnect inside a second, a restart mid-welcome and two sessions racing each other all end with
 * one moment. Nothing else here could notice it going wrong: a welcome shown twice is a curiosity
 * one person mentions once, and a welcome shown never is invisible by definition.
 *
 * <h2>After the language, never on the join</h2>
 * It is called from the same callback as {@code SystemLines#announceJoin}, which is the first moment
 * the player's language is known. Run from {@code PlayerJoinEvent} instead, the subtitle would be
 * English for everybody - {@code PlayerLocales#of} answers English until the row lands, which is the
 * whole reason the lookup is off the main thread (CLAUDE.md, and finding 96, which was exactly this
 * mistake made once already in this module).
 *
 * <h2>What is a placeholder here, and what is not</h2>
 * <b>The pictures are a placeholder and are meant to look like one.</b> The sequence itself is art
 * and belongs to the owner (todo.md A10): the finished version is several similar textures in a font
 * of this project's own, and a glyph can only be added in the resource pack, in {@code Glyphs} and in
 * a font file together. So the frames below are visibly-marked text, which nobody can mistake for
 * finished, and replacing them is a one-line change to {@link #frames()} once the glyphs exist.
 *
 * <p>Everything else is real: the claim, the ordering, the blindness, the cancel on quit and on
 * death, and the sound path. The <em>sound</em> is deliberately absent rather than guessed - see
 * {@link #cinematic}.
 */
public final class SeasonWelcome {

    /** How long each picture is on screen. */
    private static final int FRAME_TICKS = 20;

    /**
     * The placeholder sequence.
     *
     * <p>Marked, numbered and in square brackets on purpose: this is the one thing here that is
     * <em>supposed</em> to be noticed and reported by whoever sees it first. A tasteful placeholder
     * is a placeholder that ships.
     */
    private static final List<String> PLACEHOLDER_FRAMES = List.of(
            "[ nordtal intro - placeholder 1/3 ]",
            "[ nordtal intro - placeholder 2/3 ]",
            "[ nordtal intro - placeholder 3/3 ]");

    private final Plugin plugin;
    private final SmpDao dao;
    private final Identities identities;
    private final Messages messages;
    private final PlayerLocales locales;
    private final BukkitCinematics cinematics;

    public SeasonWelcome(final Plugin plugin, final SmpDao dao, final Identities identities,
                         final Messages messages, final PlayerLocales locales,
                         final BukkitCinematics cinematics) {
        this.plugin = plugin;
        this.dao = dao;
        this.identities = identities;
        this.messages = messages;
        this.locales = locales;
        this.cinematics = cinematics;
    }

    /**
     * Called once per join, on the main thread, after the player's language has landed.
     *
     * <p>Not a {@code PlayerJoinEvent} handler of its own, and that is the point: an event handler
     * would run before the language is known, and there is no second event that fires when it
     * arrives. {@code PresenceListener} owns that callback, so the call is one line there.
     */
    public void onLanguageReady(final Player player) {
        final UUID uuid = player.getUniqueId();
        // Identities is filled at pre-login by JoinGate, so this is a map read rather than a query.
        // An unlinked player has nothing to claim against - there is no smp_player row to write and
        // no way to remember that they have been welcomed - so they get no moment rather than one
        // on every join for the rest of the season. On this server that set is empty: the proxy's
        // login gate refuses an unlinked account long before it reaches the SMP.
        final Optional<String> discordId = identities.discordIdOf(uuid);
        if (discordId.isEmpty()) {
            return;
        }
        final String name = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!dao.claimWelcome(discordId.get())) {
                // The ordinary answer on every join but the first. Not a failure, and it says
                // nothing.
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> show(uuid, name, discordId.get()));
        });
    }

    /**
     * The main-thread half.
     *
     * <p>By UUID rather than the {@code Player} captured at join, for the reason {@code HeadStart}
     * was corrected for on 2026-09-08: between the claim committing and this task running the player
     * may have reconnected, and the captured instance of a reconnected player answers
     * {@code isOnline()} false for ever.
     */
    private void show(final UUID uuid, final String name, final String discordId) {
        final Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            // The one path that loses it, and the flag is already taken. Named rather than
            // repaired: this is a picture and not a payout, so re-showing it is worth a line
            // somebody can act on and not a mechanism.
            plugin.getLogger().info(name + " left in the tick after their own join, so the season's"
                    + " opening moment was claimed but never shown. To give it back:"
                    + " UPDATE smp_player SET welcome_shown = false WHERE discord_id = '"
                    + discordId + "';");
            return;
        }
        cinematics.start(player, cinematic(locales.of(uuid)));
    }

    /**
     * What the moment is made of.
     *
     * <p><b>There is deliberately no sound yet.</b> The design is a sound of this project's own,
     * arriving with the resource pack, reached through {@code sounds.yml} so that a blank key runs
     * silently until it does. Every category in {@code sounds.yml} is a {@link
     * eu.nordtal.s2.common.feedback.Feedback} constant, and that enum is the network's whole sound
     * vocabulary - "if a call site cannot be expressed with one of them, that is a question for the
     * owner and not a licence to add an eleventh". None of the ten is this moment, so the question
     * is open rather than answered with the nearest fit: borrowing {@code NETWORK_EVENT} would give
     * the season's opening the phase-switch chime, which is worse than the silence the design
     * already expects. {@link Cinematic.Builder#sound} is where it goes on the day that is decided.
     */
    private Cinematic cinematic(final Locale locale) {
        return Cinematic.builder()
                .frames(frames(), FRAME_TICKS)
                .subtitle(MessageRenderer.of(messages).get(locale, "smp.welcome.subtitle"))
                // Blindness for exactly as long as the pictures run, which is what makes them the
                // only thing on the screen. Removed by the staging when it ends, when the player
                // dies and when they log out - see BukkitCinematics.
                .effect(new Cinematic.Effect("minecraft:blindness", 0))
                .build();
    }

    /**
     * The pictures.
     *
     * <p>Plain text today. When the art exists these become one component per texture, each naming
     * the font it was drawn in - a code point without its font draws whatever another font put at
     * that code point, which is why the frames are components here rather than code points.
     */
    private static List<Component> frames() {
        final List<Component> frames = new ArrayList<>(PLACEHOLDER_FRAMES.size());
        for (final String frame : PLACEHOLDER_FRAMES) {
            // Component.text and not MessageRenderer, which is why this file is named in
            // OneMessageFormatTest's allowlist: a frame is a picture rather than a sentence, and the
            // finished ones are private-use code points that must never be written into a
            // .properties file (CLAUDE.md - a glyph reaches a bundle as a {parameter} or not at
            // all).
            frames.add(Component.text(frame));
        }
        return frames;
    }
}
