package eu.nordtal.s2.papercommon.command;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.ToneColours;
import eu.nordtal.s2.common.message.Tones;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Whoever typed a command on a Paper server - a player, or the console.
 *
 * <b>The console is always an admin</b>, and that is the single exception to
 * {@code discord_user.admin} being the only admin list. It is not a second list: the console is a
 * shell inside the container, and anybody holding one can edit that table by hand. Refusing them
 * would only remove the path that still works when the database holds no admin at all.
 *
 * The console gets English, because it has no account and therefore no language.
 *
 * Commands do their work on Bukkit's async scheduler, so every reply hops to the main thread -
 * carrying the message <em>and</em> its sound in one tick, because two hops read as lag.
 */
public final class PaperUser implements NordtalUser {

    /** How a module plays its own feedback sounds. Nothing here knows what a category sounds like. */
    @FunctionalInterface
    public interface Chime {

        void play(Player player, Feedback feedback);

        /** For a module with no sounds, and for the console, which has no ears. */
        static Chime silent() {
            return (player, feedback) -> {};
        }
    }

    private final Plugin plugin;
    private final CommandSender sender;
    private final Locale locale;
    private final boolean admin;
    private final java.util.function.Supplier<Optional<String>> discordId;
    private final Messages messages;
    private final Chime chime;
    private final java.util.function.Supplier<ToneColours> colours;

    private PaperUser(
            final Plugin plugin,
            final CommandSender sender,
            final Locale locale,
            final boolean admin,
            final java.util.function.Supplier<Optional<String>> discordId,
            final Messages messages,
            final Chime chime,
            final java.util.function.Supplier<ToneColours> colours) {
        this.plugin = plugin;
        this.sender = sender;
        this.locale = locale;
        this.admin = admin;
        this.discordId = discordId;
        this.messages = messages;
        this.chime = chime;
        this.colours = colours;
    }

    /**
     * A player who has already been looked up.
     *
     * @param admin     their {@code discord_user.admin} flag, <b>read by the caller</b> on a thread
     *                  that is allowed to wait. It is never read here: {@link #admin()} is called
     *                  from places that must not block
     * @param discordId their Discord id if the caller happens to know it, {@code null} otherwise -
     *                  "this surface does not know one" is a legitimate answer and commands are
     *                  written for it
     * @param colours   the tone palette this plugin is configured with right now - a supplier and
     *                  not a value, so a reload swaps what the next reply paints with rather than
     *                  what this already-built instance answered when it was constructed
     */
    public static PaperUser of(
            final Plugin plugin,
            final Player player,
            final Locale locale,
            final boolean admin,
            final @Nullable String discordId,
            final Messages messages,
            final Chime chime,
            final java.util.function.Supplier<ToneColours> colours) {
        return of(plugin, player, locale, admin, () -> Optional.ofNullable(discordId), messages, chime, colours);
    }

    /**
     * The same, with the Discord account resolved only if something asks. See {@link #discordId()}.
     *
     * The overload to use whenever the source is anything but a cache.
     */
    public static PaperUser of(
            final Plugin plugin,
            final Player player,
            final @Nullable Locale locale,
            final boolean admin,
            final java.util.function.Supplier<Optional<String>> discordId,
            final Messages messages,
            final @Nullable Chime chime,
            final java.util.function.Supplier<ToneColours> colours) {
        return new PaperUser(
                Objects.requireNonNull(plugin, "plugin"),
                Objects.requireNonNull(player, "player"),
                locale == null ? Locales.DEFAULT : locale,
                admin,
                discordId,
                Objects.requireNonNull(messages, "messages"),
                chime == null ? Chime.silent() : chime,
                Objects.requireNonNull(colours, "colours"));
    }

    /** The console: English, always an admin, no identities, and no sound. */
    public static PaperUser console(
            final Plugin plugin,
            final CommandSender sender,
            final Messages messages,
            final java.util.function.Supplier<ToneColours> colours) {
        return new PaperUser(
                Objects.requireNonNull(plugin, "plugin"),
                Objects.requireNonNull(sender, "sender"),
                Locales.DEFAULT,
                true,
                Optional::empty,
                Objects.requireNonNull(messages, "messages"),
                Chime.silent(),
                Objects.requireNonNull(colours, "colours"));
    }

    /** Whether this sender is the console, for a command that has to refuse one. */
    public static boolean isConsole(final CommandSender sender) {
        return sender instanceof ConsoleCommandSender;
    }

    /**
     * Their Discord account, <b>resolved when asked and not when this object is built</b>.
     *
     * A {@code PaperUser} is built inside a Brigadier handler on the main thread for every
     * invocation, so an eager lookup would be a database query there. The callers that need the
     * answer read it on their own scheduler.
     */
    @Override
    public Optional<String> discordId() {
        return discordId.get();
    }

    @Override
    public Optional<UUID> minecraftUuid() {
        return sender instanceof Player player ? Optional.of(player.getUniqueId()) : Optional.empty();
    }

    @Override
    public String name() {
        return sender instanceof Player player ? player.getName() : "console";
    }

    @Override
    public Locale locale() {
        return locale;
    }

    @Override
    public boolean admin() {
        return admin;
    }

    @Override
    public Origin origin() {
        return sender instanceof Player ? Origin.GAME : Origin.CONSOLE;
    }

    @Override
    public void reply(final MessageRef message) {
        // send(..., null): reply(message, null) would be ambiguous between the Feedback and Tone overloads.
        send(render(message), null);
    }

    @Override
    public void reply(final MessageRef message, final Feedback feedback) {
        send(render(message), feedback);
    }

    @Override
    public void reply(final MessageRef message, final Tone tone) {
        send(Tones.paint(render(message), tone, colours.get()), null);
    }

    @Override
    public void reply(final MessageRef message, final Feedback feedback, final Tone tone) {
        // One hop carrying the line, colour and chime: painting first keeps Adventure off the main thread.
        send(Tones.paint(render(message), tone, colours.get()), feedback);
    }

    @Override
    public String phrase(final MessageRef message) {
        // Plain text: the result is substituted into a message re-parsed as MiniMessage, where tags would leak through.
        return PlainTextComponentSerializer.plainText().serialize(render(message));
    }

    @Override
    public void replyLiteral(final String text) {
        send(Component.text(text), null);
    }

    private Component render(final MessageRef message) {
        return MessageRenderer.of(messages).format(locale, message);
    }

    /**
     * One hop to the main thread, carrying the line and its sound together.
     *
     * Scheduled unconditionally rather than only when off-thread, so that two replies keep the
     * order they were written in whichever thread each came from.
     */
    private void send(final Component message, final @Nullable Feedback feedback) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            sender.sendMessage(message);
            if (feedback != null && sender instanceof Player player) {
                chime.play(player, feedback);
            }
        });
    }
}
