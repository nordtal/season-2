package eu.nordtal.s2.papercommon.command;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.database.command.AllowlistDirectory;
import eu.nordtal.s2.database.command.CommandAllowlist;
import eu.nordtal.s2.database.notify.Channels;
import eu.nordtal.s2.database.notify.NotificationListener;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messagerendering.ToneColours;
import eu.nordtal.s2.messagerendering.Tones;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.PlayerLocales;
import eu.nordtal.s2.messages.Tone;
import eu.nordtal.s2.messages.feedback.Feedback;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.command.UnknownCommandEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The Paper half of the command allowlist: it hides what the proxy refuses from each player's command tree.
 *
 * Until the proxy has published a list it does nothing and warns, since the proxy's own enforcement stands.
 */
public final class CommandFilter implements Listener {

    /** Where the published list is read from. */
    @FunctionalInterface
    public interface Source {

        /** Returns the current list, or empty when no proxy has ever published one. */
        Optional<CommandAllowlist> read();

        /** Returns the ordinary one: {@code AllowlistDirectory} over the plugin's own pool. */
        static Source of(final AllowlistDirectory directory) {
            Objects.requireNonNull(directory, "directory");
            return directory::published;
        }
    }

    private final Plugin plugin;
    private final Source source;
    private final Predicate<UUID> admin;
    private final PlayerLocales locales;
    private final Messages messages;
    private final Logger logger;
    private final PaperUser.Chime chime;
    private final java.util.function.Supplier<ToneColours> colours;

    /** The list as of the last successful read, or {@code null} while none has arrived. */
    private volatile @Nullable CommandAllowlist active;

    /** So that "nothing has been published" warns once, not once per poll. */
    private volatile boolean warnedAboutMissingList;

    /** Creates one without a {@link PaperUser.Chime}, so the refusal plays no sound. */
    public CommandFilter(
            final Plugin plugin,
            final Source source,
            final Predicate<UUID> admin,
            final PlayerLocales locales,
            final Messages messages,
            final Logger logger,
            final java.util.function.Supplier<ToneColours> colours) {
        this(plugin, source, admin, locales, messages, logger, colours, PaperUser.Chime.silent());
    }

    /**
     * @param colours the current tone palette, a supplier so a reload reaches the next refusal
     * @param chime   how the refusal sounds; {@link PaperUser.Chime#silent()} for a module with no sounds
     */
    public CommandFilter(
            final Plugin plugin,
            final Source source,
            final Predicate<UUID> admin,
            final PlayerLocales locales,
            final Messages messages,
            final Logger logger,
            final java.util.function.Supplier<ToneColours> colours,
            final PaperUser.Chime chime) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.source = Objects.requireNonNull(source, "source");
        this.admin = Objects.requireNonNull(admin, "admin");
        this.locales = Objects.requireNonNull(locales, "locales");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.colours = Objects.requireNonNull(colours, "colours");
        this.chime = Objects.requireNonNull(chime, "chime");
    }

    /** Starts the poll that is the guarantee, async and first on the next tick. */
    public void start(final Duration pollInterval) {
        final long ticks = Math.max(20L, pollInterval.toSeconds() * 20L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 1L, ticks);
    }

    /** Returns the refreshes to hand to {@code AdminWatch#start}, so the instant path shares its connection. */
    public List<NotificationListener.Refresh> refreshes() {
        return List.of(new NotificationListener.Refresh("the command allowlist", this::refresh));
    }

    /** Returns the channels to hand to {@code AdminWatch#start} alongside {@link #refreshes()}. */
    public List<String> channels() {
        return List.of(Channels.ALLOWLIST);
    }

    /**
     * Re-reads the whole list; never call this on the main thread.
     *
     * A failure keeps the previous list, so an unreachable database neither opens nor closes the server.
     */
    public void refresh() {
        final Optional<CommandAllowlist> read;
        try {
            read = source.read();
        } catch (final RuntimeException failure) {
            logger.warn("Could not read the command allowlist; the previous one still applies.", failure);
            return;
        }
        if (read.isEmpty()) {
            if (!warnedAboutMissingList) {
                warnedAboutMissingList = true;
                logger.warn("No command allowlist has been published yet, so this server filters"
                        + " nothing: every vanilla command is visible and typeable here. The proxy"
                        + " publishes the list from network.yml when it starts, and it enforces the"
                        + " same list itself in the meantime.");
            }
            return;
        }
        final CommandAllowlist arrived = read.get();
        final CommandAllowlist before = active;
        active = arrived;
        warnedAboutMissingList = false;
        if (!arrived.equals(before)) {
            logger.info("The command allowlist is now: {}", arrived);
        }
    }

    /** Refuses a command the list does not carry, first and by cancelling rather than rewriting. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        final CommandAllowlist current = active;
        if (current == null || admin.test(event.getPlayer().getUniqueId()) || current.allows(event.getMessage())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(refusal(event.getPlayer().getUniqueId()));
        chime.play(event.getPlayer(), Feedback.REFUSED);
    }

    /**
     * Answers an unknown command with the same line a refused one gets, so a player cannot tell which exists.
     *
     * The console keeps vanilla's text, since the parse position is diagnosis.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUnknownCommand(final UnknownCommandEvent event) {
        if (!(event.getSender() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        event.message(refusal(player.getUniqueId()));
        chime.play(player, Feedback.REFUSED);
    }

    /** Returns the one refusal line, in that player's {@code discord_user.locale}. */
    private net.kyori.adventure.text.Component refusal(final UUID player) {
        return Tones.paint(
                MessageRenderer.of(messages)
                        .format(locales.of(player), MESSAGES.command().unknown()),
                Tone.BAD,
                colours.get());
    }

    /**
     * Takes every command the list does not carry out of the tree this player is sent.
     *
     * It hides the command from completion; {@link #onCommand} is what stops it being typed.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandSend(final PlayerCommandSendEvent event) {
        final CommandAllowlist current = active;
        if (current == null || admin.test(event.getPlayer().getUniqueId())) {
            return;
        }
        event.getCommands().removeIf(label -> !current.allowsRoot(label));
    }
}
