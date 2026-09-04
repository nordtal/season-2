package eu.nordtal.s2.papercommon.command;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Whoever typed a command on a Paper server - a player, or the console.
 *
 * <h2>Why the console is a first-class case here</h2>
 * Because it was not one, and that was a real defect rather than a gap. Every {@code /hg} handler
 * cast its sender to {@link Player}, and the Brigadier tree gated every subcommand on
 * {@code getSender() instanceof Player} - so the console could run <b>none</b> of it, and the start
 * of the season's flagship event depended on exactly one client being able to connect and stay
 * connected. {@code /phase} has had a documented second path for that failure since 2026-08-31;
 * {@code /hg} had nothing, and nothing said so.
 *
 * <h2>The console is an admin, and that is the one place something other than the database decides</h2>
 * {@code discord_user.admin} is the only admin list in this repository (docs/smp.md#admins), and this
 * is its single exception. It is not a second list: the console is a shell inside the container, and
 * anybody holding one can edit that table by hand. Refusing them would protect nothing and would
 * remove the one path that still works when the database holds no admin at all.
 *
 * <h2>The language, and why the console gets English</h2>
 * A player's language is {@code discord_user.locale} through {@code account_link}, resolved by
 * {@code PlayerLocales} on the join it already reads (docs/i18n.md). The console has no account and
 * therefore no language; English is the fallback everywhere in this repository, and a console line is
 * read by an operator next to a log file that is English anyway.
 *
 * <h2>Every reply hops to the main thread</h2>
 * Commands here do their work on Bukkit's async scheduler - the rule since 2026-09-01 - so a reply
 * arrives from a thread that must not touch a player. The hop carries the message <em>and</em> its
 * sound in one tick: two hops is exactly the seam that reads as lag.
 */
public final class PaperUser implements NordtalUser {

    /** How a module plays its own feedback sounds. Nothing here knows what a category sounds like. */
    @FunctionalInterface
    public interface Chime {

        void play(Player player, Feedback feedback);

        /** For a module with no sounds, and for the console, which has no ears. */
        static Chime silent() {
            return (player, feedback) -> { };
        }
    }

    private final Plugin plugin;
    private final CommandSender sender;
    private final Locale locale;
    private final boolean admin;
    private final String discordId;
    private final Messages messages;
    private final Chime chime;

    private PaperUser(final Plugin plugin, final CommandSender sender, final Locale locale,
                      final boolean admin, final String discordId, final Messages messages,
                      final Chime chime) {
        this.plugin = plugin;
        this.sender = sender;
        this.locale = locale;
        this.admin = admin;
        this.discordId = discordId;
        this.messages = messages;
        this.chime = chime;
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
     */
    public static PaperUser of(final Plugin plugin, final Player player, final Locale locale,
                               final boolean admin, final String discordId, final Messages messages,
                               final Chime chime) {
        return new PaperUser(Objects.requireNonNull(plugin, "plugin"),
                Objects.requireNonNull(player, "player"),
                locale == null ? Locales.DEFAULT : locale,
                admin, discordId, Objects.requireNonNull(messages, "messages"),
                chime == null ? Chime.silent() : chime);
    }

    /** The console: English, always an admin, no identities, and no sound. */
    public static PaperUser console(final Plugin plugin, final CommandSender sender,
                                    final Messages messages) {
        return new PaperUser(Objects.requireNonNull(plugin, "plugin"),
                Objects.requireNonNull(sender, "sender"),
                Locales.DEFAULT, true, null, Objects.requireNonNull(messages, "messages"),
                Chime.silent());
    }

    /** Whether this sender is the console, for a command that has to refuse one. */
    public static boolean isConsole(final CommandSender sender) {
        return sender instanceof ConsoleCommandSender;
    }

    @Override
    public Optional<String> discordId() {
        return Optional.ofNullable(discordId);
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
    public void reply(final String messageKey, final Map<String, ?> placeholders) {
        reply(messageKey, placeholders, null);
    }

    @Override
    public void reply(final String messageKey, final Map<String, ?> placeholders,
                      final Feedback feedback) {
        send(render(messageKey, placeholders), feedback);
    }

    @Override
    public String phrase(final String messageKey) {
        // Plain text: the result is substituted into another message that is itself parsed as
        // MiniMessage, and a component serialised back into that string would arrive as tags.
        return PlainTextComponentSerializer.plainText().serialize(render(messageKey, Map.of()));
    }

    @Override
    public void replyLiteral(final String text) {
        send(Component.text(text), null);
    }

    private Component render(final String messageKey, final Map<String, ?> placeholders) {
        final Object[] flattened = new Object[placeholders.size() * 2];
        int index = 0;
        for (final Map.Entry<String, ?> entry : placeholders.entrySet()) {
            flattened[index++] = entry.getKey();
            flattened[index++] = String.valueOf(entry.getValue());
        }
        return MessageRenderer.of(messages).format(locale, messageKey, flattened);
    }

    /**
     * One hop to the main thread, carrying the line and its sound together.
     *
     * <p>Scheduled unconditionally rather than only when off-thread: {@code isPrimaryThread} would
     * make the ordering of two replies depend on which thread each was sent from, and a command
     * that says two things has to say them in the order it wrote them.</p>
     */
    private void send(final Component message, final Feedback feedback) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            sender.sendMessage(message);
            if (feedback != null && sender instanceof Player player) {
                chime.play(player, feedback);
            }
        });
    }
}
