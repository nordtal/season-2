package eu.nordtal.season.papercommon.command;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messagerendering.ToneColours;
import eu.nordtal.season.messagerendering.Tones;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.time.PaperScheduler;
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

/** A player or the console who typed a command. The console is an admin and speaks English. */
public final class PaperUser {

    /** Plays the feedback sounds of a module. */
    @FunctionalInterface
    public interface Chime {

        void play(Player player, Feedback feedback);

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
     * Returns a player who has been looked up.
     *
     * @param admin their {@code discord_user.admin} flag
     * @param colours the tone palette, a supplier, so that a reload reaches the next reply
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

    /** Returns the same player, with the Discord account resolved only when asked. */
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

    /** Returns the console: English, always an admin, without identities and sound, names drawn bare. */
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

    public static boolean isConsole(final CommandSender sender) {
        return sender instanceof ConsoleCommandSender;
    }

    /** Returns the Discord account, resolved when asked, because the lookup queries the database. */
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

    /** Sends a game document, whose text carries its tones. */
    public void reply(final Sent<?> message) {
        send(renderer.format(locale, message), null);
    }

    public void reply(final Sent<?> message, final Feedback feedback) {
        send(renderer.format(locale, message), feedback);
    }

    /** Sends a refusal's line, which every refusal shows in the warning tone. */
    public void refuse(final MessageRef refusal) {
        send(Tones.paint(render(refusal), Tone.WARN, colours.get()), null);
    }

    public String phrase(final MessageRef message) {
        // The result is inserted into a message that is parsed as MiniMessage again.
        return PlainTextComponentSerializer.plainText().serialize(render(message));
    }

    public void replyLiteral(final String text) {
        send(Component.text(text), null);
    }

    private Component render(final MessageRef message) {
        return renderer.format(locale, message);
    }

    private void send(final Component message, final @Nullable Feedback feedback) {
        PaperScheduler.of(plugin).onMain(() -> {
            sender.sendMessage(message);
            if (feedback != null && sender instanceof Player player) {
                chime.play(player, feedback);
            }
        });
    }
}
