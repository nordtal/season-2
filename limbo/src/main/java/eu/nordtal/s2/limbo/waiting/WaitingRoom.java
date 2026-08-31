package eu.nordtal.s2.limbo.waiting;

import eu.nordtal.s2.common.limbo.WaitReason;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.limbo.config.LimboSpec;
import eu.nordtal.s2.limbo.world.WaitingWorld;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The waiting room's entire interface: one title per player, in that player's language, saying what
 * they are waiting for.
 *
 * <p>docs/architecture.md, decided 2026-08-31: "<b>What limbo shows: nothing.</b> Black, no visible
 * world, no other players and <b>no chat</b>. A title in the player's language says what they are
 * waiting for, and that is the entire interface."
 *
 * <h2>The three things that make a black screen</h2>
 * <ol>
 *   <li><b>An empty world</b>, which is {@link WaitingWorld}'s job - but an empty world is a sky,
 *       not a black screen.</li>
 *   <li><b>Blindness</b>, which is what makes it actually black. Infinite, with no particles and no
 *       icon, so the effect itself is invisible.</li>
 *   <li><b>Hidden players</b>, done by the listener rather than here: two people waiting must not
 *       see each other, and the proxy may have several in here at once.</li>
 * </ol>
 *
 * <h2>Why the title is re-sent</h2>
 * A Minecraft title expires. On a server whose only content is that title, an expired one is a
 * completely black screen with nothing on it - indistinguishable, to the person looking at it, from
 * a client that has hung. So it is refreshed on a timer, with <b>no fade</b>, which replaces the
 * text in place instead of re-animating it. A <em>change</em> of reason does fade in, because that
 * is a real event and the player should notice it.
 *
 * <h2>What it does not decide</h2>
 * Which reason to show. The proxy sends that ({@code eu.nordtal.s2.common.limbo.LimboProtocol}), and
 * until it does, {@link WaitReason#UNKNOWN} says so rather than leaving the screen empty.
 */
public final class WaitingRoom {

    private final Plugin plugin;
    private final LimboSpec config;
    private final Messages messages;
    private final PlayerLocales locales;
    private final WaitingWorld world;

    private final Map<UUID, WaitReason> shown = new ConcurrentHashMap<>();

    private BukkitTask refresh;

    public WaitingRoom(final Plugin plugin, final LimboSpec config, final Messages messages,
                       final PlayerLocales locales, final WaitingWorld world) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.locales = Objects.requireNonNull(locales, "locales");
        this.world = Objects.requireNonNull(world, "world");
    }

    /**
     * Puts a player into the state the waiting room keeps everybody in: adventure mode, flying,
     * invulnerable, fed, blind, and holding nothing.
     *
     * @param player the player who has just joined
     */
    public void receive(final Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.setFireTicks(0);
        player.setHealth(Objects.requireNonNull(player.getAttribute(
                org.bukkit.attribute.Attribute.MAX_HEALTH)).getValue());
        player.setExp(0.0f);
        player.setLevel(0);
        player.getInventory().clear();
        player.teleport(world.spawn());

        if (config.blindness()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                    PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }

        // No reason yet - the proxy's WAIT arrives moments from now. Something has to be on screen
        // in the meantime, because a black screen with no text is what a crash looks like.
        show(player, WaitReason.UNKNOWN, true);
    }

    /**
     * Shows a reason, fading it in only if it is different from the one already up.
     *
     * @param player the player
     * @param reason what they are waiting for
     */
    public void show(final Player player, final WaitReason reason) {
        show(player, reason, shown.get(player.getUniqueId()) != reason);
    }

    private void show(final Player player, final WaitReason reason, final boolean fade) {
        shown.put(player.getUniqueId(), reason);

        final Locale locale = locales.of(player.getUniqueId());
        final Duration stay = Duration.ofSeconds(config.titleRefreshSeconds() * 2L);
        final Title.Times times = fade
                ? Title.Times.times(Duration.ofMillis(300), stay, Duration.ofMillis(200))
                : Title.Times.times(Duration.ZERO, stay, Duration.ZERO);

        player.showTitle(Title.title(
                Component.text(messages.get(locale, reason.titleKey())),
                Component.text(messages.get(locale, reason.subtitleKey())),
                times));
    }

    /**
     * Starts the refresh loop. One task for the whole server rather than one per player: the set is
     * usually empty and never large, and a per-player task on a login path is a task created and
     * cancelled thousands of times a day for no reason.
     */
    public void start() {
        final long ticks = config.titleRefreshSeconds() * 20L;
        refresh = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, ticks, ticks);
    }

    /** Stops the refresh loop. */
    public void stop() {
        if (refresh != null) {
            refresh.cancel();
            refresh = null;
        }
        shown.clear();
    }

    /**
     * Re-sends whatever this player already has on screen, without a fade.
     * <p>
     * Used when the player's language arrives after the title has already been drawn - the join
     * path reads it off the main thread, so the first title of every session may be English. There
     * is nothing to decide here: the reason has not changed, only the words it renders into.
     * </p>
     *
     * @param player the player
     */
    public void redraw(final Player player) {
        show(player, shown.getOrDefault(player.getUniqueId(), WaitReason.UNKNOWN), false);
    }

    /** Forgets a player. Called on quit, or the map grows for the lifetime of the process. */
    public void forget(final UUID uuid) {
        shown.remove(uuid);
    }

    /** @return which reason each waiting player currently has on screen, for tests and logging */
    public Map<UUID, WaitReason> shown() {
        return Map.copyOf(shown);
    }

    private void tick() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final WaitReason reason = shown.getOrDefault(player.getUniqueId(), WaitReason.UNKNOWN);
            show(player, reason, false);

            if (world.hasStrayed(player.getLocation())) {
                // Flying costs nothing in an empty world, but falling out of one streams chunks
                // after somebody who is looking at a black screen. Put them back without comment.
                player.teleport(world.spawn());
                player.setFlying(true);
            }
        }
    }
}
