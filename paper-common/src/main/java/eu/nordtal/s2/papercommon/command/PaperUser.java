package eu.nordtal.s2.papercommon.command;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messagerendering.ToneColours;
import eu.nordtal.s2.messagerendering.Tones;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Tone;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.papercommon.time.PaperScheduler;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * A player or the console that typed a command on a Paper server.
 *
 * The console is always an admin and speaks English; replies hop to the main thread with their sound in one tick.
 */
public final class PaperUser {

    /** How a module plays its own feedback sounds. */
    @FunctionalInterface
    public interface Chime {

        void play(Player player, Feedback feedback);

        /** Returns a chime that plays nothing, for a module with no sounds and for the console. */
        static Chime silent() {
            return (player, feedback) -> {};
        }
    }

    private final Plugin plugin;
    private final CommandSender sender;
    private final Locale locale;
    private final boolean admin;
    private final java.util.function.Supplier<Optional<DiscordId>> discordId;
    private final MessageRenderer renderer;
    private final Chime chime;
    private final java.util.function.Supplier<ToneColours> colours;

    private PaperUser(
            final Plugin plugin,
            final CommandSender sender,
            final Locale locale,
            final boolean admin,
            final java.util.function.Supplier<Optional<DiscordId>> discordId,
            final MessageRenderer renderer,
            final Chime chime,
            final java.util.function.Supplier<ToneColours> colours) {
        this.plugin = plugin;
        this.sender = sender;
        this.locale = locale;
        this.admin = admin;
        this.discordId = discordId;
        this.renderer = renderer;
        this.chime = chime;
        this.colours = colours;
    }

    /**
     * Returns a player who has already been looked up.
     *
     * @param admin their {@code discord_user.admin} flag, read by the caller off the main thread
     * @param discordId their Discord id, or {@code null} if the caller does not know it
     * @param colours the current tone palette, a supplier so that a reload reaches the next reply
     */
    public static PaperUser of(
            final Plugin plugin,
            final Player player,
            final Locale locale,
            final boolean admin,
            final @Nullable DiscordId discordId,
            final MessageRenderer renderer,
            final Chime chime,
            final java.util.function.Supplier<ToneColours> colours) {
        return of(plugin, player, locale, admin, () -> Optional.ofNullable(discordId), renderer, chime, colours);
    }

    /** Returns the same, with the Discord account resolved only when asked; use it unless the source is a cache. */
    public static PaperUser of(
            final Plugin plugin,
            final Player player,
            final @Nullable Locale locale,
            final boolean admin,
            final java.util.function.Supplier<Optional<DiscordId>> discordId,
            final MessageRenderer renderer,
            final @Nullable Chime chime,
            final java.util.function.Supplier<ToneColours> colours) {
        return new PaperUser(
                Objects.requireNonNull(plugin, "plugin"),
                Objects.requireNonNull(player, "player"),
                locale == null ? Locales.DEFAULT : locale,
                admin,
                discordId,
                Objects.requireNonNull(renderer, "renderer"),
                chime == null ? Chime.silent() : chime,
                Objects.requireNonNull(colours, "colours"));
    }

    /** Returns the console: English, always an admin, no identities, no sound, and names drawn bare. */
    public static PaperUser console(
            final Plugin plugin,
            final CommandSender sender,
            final MessageRenderer renderer,
            final java.util.function.Supplier<ToneColours> colours) {
        return new PaperUser(
                Objects.requireNonNull(plugin, "plugin"),
                Objects.requireNonNull(sender, "sender"),
                Locales.DEFAULT,
                true,
                Optional::empty,
                Objects.requireNonNull(renderer, "renderer").bare(),
                Chime.silent(),
                Objects.requireNonNull(colours, "colours"));
    }

    /** Returns whether this sender is the console. */
    public static boolean isConsole(final CommandSender sender) {
        return sender instanceof ConsoleCommandSender;
    }

    /**
     * Returns their Discord account, resolved when asked rather than when built.
     *
     * A {@code PaperUser} is built on the main thread per invocation, where an eager lookup would query the database.
     */
    public Optional<DiscordId> discordId() {
        return discordId.get();
    }

    public Optional<UUID> minecraftUuid() {
        return sender instanceof Player player ? Optional.of(player.getUniqueId()) : Optional.empty();
    }

    public String name() {
        return sender instanceof Player player ? player.getName() : "console";
    }

    public Locale locale() {
        return locale;
    }

    public boolean admin() {
        return admin;
    }

    public void reply(final MessageRef message) {
        // send(..., null): reply(message, null) would be ambiguous between the Feedback and Tone overloads.
        send(render(message), null);
    }

    public void reply(final MessageRef message, final Feedback feedback) {
        send(render(message), feedback);
    }

    public void reply(final MessageRef message, final Tone tone) {
        send(Tones.paint(render(message), tone, colours.get()), null);
    }

    public void reply(final MessageRef message, final Feedback feedback, final Tone tone) {
        // One hop carrying the line, colour and chime: painting first keeps Adventure off the main thread.
        send(Tones.paint(render(message), tone, colours.get()), feedback);
    }

    public String phrase(final MessageRef message) {
        // Plain text: the result is substituted into a message re-parsed as MiniMessage, where tags would leak through.
        return PlainTextComponentSerializer.plainText().serialize(render(message));
    }

    public void replyLiteral(final String text) {
        send(Component.text(text), null);
    }

    private Component render(final MessageRef message) {
        return renderer.format(locale, message);
    }

    /** Sends on the main thread, always scheduled, so that replies keep their order across threads. */
    private void send(final Component message, final @Nullable Feedback feedback) {
        PaperScheduler.of(plugin).onMain(() -> {
            sender.sendMessage(message);
            if (feedback != null && sender instanceof Player player) {
                chime.play(player, feedback);
            }
        });
    }
}
