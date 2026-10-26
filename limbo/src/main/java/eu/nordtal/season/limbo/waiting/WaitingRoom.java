package eu.nordtal.season.limbo.waiting;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.limbo.config.LimboSpec;
import eu.nordtal.season.limbo.world.WaitingWorld;
import eu.nordtal.season.limboprotocol.WaitReason;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
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
import org.jspecify.annotations.Nullable;

/**
 * Shows each player a title with the reason for the wait, in the language of the player.
 * A timer sends the title again every {@code title-refresh-seconds}.
 */
public final class WaitingRoom {

    /** How far the running update is; {@code counted} is false when the proxy sent no number. */
    record Progress(boolean counted, @Example("75") long percent) {}

    static final DocumentSpec<DocumentSpec.None> PACK = screen("resource-pack").titled("Resource pack loading");
    static final DocumentSpec<DocumentSpec.None> BACKEND =
            screen("server-not-up").titled("Server not up yet");
    static final DocumentSpec<Progress> UPDATE = DocumentSpec.game(
                    "limbo", "update", "Waiting room", DocumentSpec.Shape.TITLE, Progress.class)
            .titled("Update running");
    static final DocumentSpec<DocumentSpec.None> HELD = screen("server-stopped").titled("Server stopped");
    static final DocumentSpec<DocumentSpec.None> QUEUE = screen("next-in-line").titled("Next in line");
    static final DocumentSpec<DocumentSpec.None> MAINTENANCE =
            screen("maintenance").titled("Maintenance");
    static final DocumentSpec<DocumentSpec.None> UNKNOWN =
            screen("unknown-reason").titled("Waiting without a reason");

    private final Plugin plugin;
    private final LimboSpec config;
    private final MessageRenderer renderer;
    private final Identities identities;
    private final WaitingWorld world;

    private final Map<UUID, WaitReason> shown = new ConcurrentHashMap<>();

    /** The percentage of the running update, for the players who see its title. */
    private final Map<UUID, Integer> percents = new ConcurrentHashMap<>();

    private Scheduler.@Nullable Task refresh;

    public WaitingRoom(
            final Plugin plugin,
            final LimboSpec config,
            final MessageRenderer renderer,
            final Identities identities,
            final WaitingWorld world) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.world = Objects.requireNonNull(world, "world");
    }

    /** Sets the player to adventure mode, flying and invulnerable, with full health and food and no items. */
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

        show(player, WaitReason.UNKNOWN, true);
    }

    /**
     * Shows the title of the reason. The title fades in when the player saw another reason before.
     *
     * @param percent how far the running update is, {@code null} when the proxy sent no number
     */
    public void show(final Player player, final WaitReason reason, final @Nullable Integer percent) {
        final boolean fade = shown.get(player.getUniqueId()) != reason;
        if (percent == null) {
            percents.remove(player.getUniqueId());
        } else {
            percents.put(player.getUniqueId(), percent);
        }
        show(player, reason, fade);
    }

    private void show(final Player player, final WaitReason reason, final boolean fade) {
        shown.put(player.getUniqueId(), reason);

        final Locale locale = identities.languageOf(player.getUniqueId());
        final Duration stay = Duration.ofSeconds(config.titleRefreshSeconds() * 2L);
        final Title.Times times = fade
                ? Title.Times.times(Duration.ofMillis(300), stay, Duration.ofMillis(200))
                : Title.Times.times(Duration.ZERO, stay, Duration.ZERO);

        player.showTitle(renderer.title(locale, screen(reason, percents.get(player.getUniqueId())), times));
    }

    /** Returns the screen of the reason, the update's with its percentage when the proxy sent one. */
    static Sent<?> screen(final WaitReason reason, final @Nullable Integer percent) {
        return switch (reason) {
            case PACK -> Sent.of(PACK);
            case BACKEND -> Sent.of(BACKEND);
            case UPDATE -> UPDATE.with(new Progress(percent != null, percent == null ? 0 : percent));
            case HELD -> Sent.of(HELD);
            case QUEUE -> Sent.of(QUEUE);
            case MAINTENANCE -> Sent.of(MAINTENANCE);
            case UNKNOWN -> Sent.of(UNKNOWN);
        };
    }

    private static DocumentSpec<DocumentSpec.None> screen(final String name) {
        return DocumentSpec.game("limbo", name, "Waiting room", DocumentSpec.Shape.TITLE);
    }

    /** Starts the timer that sends the titles. */
    public void start() {
        final Duration period = Duration.ofSeconds(config.titleRefreshSeconds());
        refresh = PaperScheduler.of(plugin).onMainEvery(period, period, this::tick);
    }

    /** Stops the timer. */
    public void stop() {
        if (refresh != null) {
            refresh.cancel();
            refresh = null;
        }
        shown.clear();
        percents.clear();
    }

    /** Sends the current title of the player again, without a fade. */
    public void redraw(final Player player) {
        show(player, shown.getOrDefault(player.getUniqueId(), WaitReason.UNKNOWN), false);
    }

    /** Deletes the reason stored for the player. */
    public void forget(final UUID uuid) {
        shown.remove(uuid);
        percents.remove(uuid);
    }

    /** Returns the reason shown to each player. */
    public Map<UUID, WaitReason> shown() {
        return Map.copyOf(shown);
    }

    private void tick() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final WaitReason reason = shown.getOrDefault(player.getUniqueId(), WaitReason.UNKNOWN);
            show(player, reason, false);

            if (world.hasStrayed(player.getLocation())) {
                player.teleport(world.spawn());
                player.setFlying(true);
            }
        }
    }
}
