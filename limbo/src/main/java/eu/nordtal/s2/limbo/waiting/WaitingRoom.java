package eu.nordtal.s2.limbo.waiting;

import eu.nordtal.s2.limbo.LimboMessages;
import eu.nordtal.s2.limbo.config.LimboSpec;
import eu.nordtal.s2.limbo.world.WaitingWorld;
import eu.nordtal.s2.limboprotocol.WaitReason;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.PlayerLocales;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * The waiting room's entire interface: one title per player, in that player's own language, saying what they wait for.
 *
 * Refreshed on a timer with no fade, since an expired title leaves a black screen that looks hung.
 */
public final class WaitingRoom {

    private final Plugin plugin;
    private final LimboSpec config;
    private final Messages messages;
    private final PlayerLocales locales;
    private final WaitingWorld world;

    private final Map<UUID, WaitReason> shown = new ConcurrentHashMap<>();

    private @Nullable BukkitTask refresh;

    public WaitingRoom(
            final Plugin plugin,
            final LimboSpec config,
            final Messages messages,
            final PlayerLocales locales,
            final WaitingWorld world) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.locales = Objects.requireNonNull(locales, "locales");
        this.world = Objects.requireNonNull(world, "world");
    }

    /**
     * Puts a player into the waiting room's held state: adventure, flying, invulnerable, fed, blind, holding nothing.
     */
    public void receive(final Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.setFireTicks(0);
        player.setHealth(Objects.requireNonNull(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH))
                .getValue());
        player.setExp(0.0f);
        player.setLevel(0);
        player.getInventory().clear();
        player.teleport(world.spawn());

        if (config.blindness()) {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.BLINDNESS, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }

        // Something has to be on screen before the proxy's WAIT arrives: a blank one looks like a crash.
        show(player, WaitReason.UNKNOWN, true);
    }

    /** Shows a reason, fading it in only if it is different from the one already up. */
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

        final LimboMessages.Limbo.Screen screen =
                LimboMessages.MESSAGES.limbo().waiting().of(reason);
        player.showTitle(Title.title(
                MessageRenderer.of(messages).format(locale, screen.title()),
                MessageRenderer.of(messages).format(locale, screen.subtitle()),
                times));
    }

    /** Starts the refresh loop, one task for the whole server rather than one per player. */
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
     *
     * Used when the player's language arrives after the first title was drawn in English.
     */
    public void redraw(final Player player) {
        show(player, shown.getOrDefault(player.getUniqueId(), WaitReason.UNKNOWN), false);
    }

    /** Forgets a player. Called on quit, or the map grows for the lifetime of the process. */
    public void forget(final UUID uuid) {
        shown.remove(uuid);
    }

    /** Returns which reason each waiting player currently has on screen. */
    public Map<UUID, WaitReason> shown() {
        return Map.copyOf(shown);
    }

    private void tick() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final WaitReason reason = shown.getOrDefault(player.getUniqueId(), WaitReason.UNKNOWN);
            show(player, reason, false);

            if (world.hasStrayed(player.getLocation())) {
                // Falling out of an empty world streams chunks after a player looking at a black screen.
                player.teleport(world.spawn());
                player.setFlying(true);
            }
        }
    }
}
