package eu.nordtal.s2.proxy.command;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.ToneColours;
import eu.nordtal.s2.common.message.context.PlayerContext;
import eu.nordtal.s2.proxy.ProxyMessages;
import eu.nordtal.s2.proxy.gate.LoginRoster;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The network's own private messages: {@code /msg}, {@code /whisper} and {@code /r}.
 *
 * The proxy owns them because it is the only process that can see both people: vanilla's
 * {@code /tell} is per-server, so a conversation would end the moment one side crosses to another
 * backend, and on this network crossing is normal. The proxy also already holds what a line has to
 * be drawn from, the language each side reads and their admin flag, both out of
 * {@link LoginRoster}.
 *
 * {@code /whisper} is built from the same method as {@code /msg}, registered a second time rather
 * than aliased through a Brigadier redirect, so tab completion, the usage line and the command
 * allowlist see two ordinary commands.
 *
 * The message travels through {@link MessageRenderer}'s component slot rather than a substituted
 * string, so the player's text never reaches the MiniMessage parser and nobody can colour a line
 * about themselves. A line carries the flag and the admin tag, both already held from the login
 * query, but never the SMP's prestige crest or aura, which would mean a database query per message
 * on the process that must not make one; it also keeps a whisper from looking exactly like
 * ordinary chat, which somebody could otherwise answer in public by mistake.
 *
 * Nothing is written down: no log line carries the text, no admin channel is told, no table is
 * touched. The only state here is who last spoke to whom, in memory, dropped on disconnect.
 */
public final class PrivateMessages {

    /** The name of the recipient argument, which is also what tab completion offers against. */
    static final String PLAYER = "player";

    /** Everything after the name, taken whole. */
    static final String MESSAGE = "message";

    private final ProxyServer proxy;
    private final LoginRoster roster;
    private final Messages messages;
    private final Supplier<ToneColours> colours;
    private final Logger logger;

    /**
     * Who each connected player last exchanged a private message with, for {@code /r}.
     *
     * Set by both sides of every message, which is what makes an answer possible without either of
     * them having typed a name. It is held here and dies with the process: writing it down would
     * mean a table of who talks to whom, which is a record of exactly the thing this feature is
     * built not to keep. The cost is that a proxy restart makes everybody's next {@code /r} say
     * there is nobody to reply to.
     */
    private final ConcurrentHashMap<UUID, UUID> partners = new ConcurrentHashMap<>();

    public PrivateMessages(
            final ProxyServer proxy,
            final LoginRoster roster,
            final Messages messages,
            final Supplier<ToneColours> colours,
            final Logger logger) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.colours = Objects.requireNonNull(colours, "colours");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** All three, ready for {@code CommandManager#register}. */
    public List<BrigadierCommand> commands() {
        return List.of(whisper("msg"), whisper("whisper"), reply());
    }

    private BrigadierCommand whisper(final String literal) {
        final String usage = "/" + literal + " <" + PLAYER + "> <" + MESSAGE + ">";
        final ProxyMessages.Command.DescribeMessages describes =
                ProxyMessages.MESSAGES.command().describe();
        final MessageRef describe = "msg".equals(literal) ? describes.msg() : describes.whisper();

        final RequiredArgumentBuilder<CommandSource, ?> text =
                BrigadierCommand.requiredArgumentBuilder(MESSAGE, StringArgumentType.greedyString());
        text.executes(this::runWhisper);

        final RequiredArgumentBuilder<CommandSource, ?> who =
                BrigadierCommand.requiredArgumentBuilder(PLAYER, StringArgumentType.word());
        // A map lookup and never a query - Brigadier evaluates this while building the client's tree.
        who.suggests((context, builder) -> {
            proxy.getAllPlayers().forEach(online -> builder.suggest(online.getUsername()));
            return builder.buildFuture();
        });
        who.executes(context -> usage(context, usage, describe));
        who.then(text);

        return new BrigadierCommand(BrigadierCommand.literalArgumentBuilder(literal)
                .then(who)
                .executes(context -> usage(context, usage, describe)));
    }

    private BrigadierCommand reply() {
        final RequiredArgumentBuilder<CommandSource, ?> text =
                BrigadierCommand.requiredArgumentBuilder(MESSAGE, StringArgumentType.greedyString());
        text.executes(this::runReply);
        return new BrigadierCommand(BrigadierCommand.literalArgumentBuilder("r")
                .then(text)
                .executes(context -> usage(
                        context,
                        "/r <" + MESSAGE + ">",
                        ProxyMessages.MESSAGES.command().describe().r())));
    }

    private int runWhisper(final CommandContext<CommandSource> context) {
        final Player sender = sender(context);
        if (sender == null) {
            return Command.SINGLE_SUCCESS;
        }
        final String name = StringArgumentType.getString(context, PLAYER);
        final String text = StringArgumentType.getString(context, MESSAGE);
        final Optional<Player> recipient = proxy.getPlayer(name);
        if (recipient.isEmpty()) {
            user(sender).reply(MESSAGES.command().playerOffline(), Feedback.REFUSED, Tone.WARN);
            return Command.SINGLE_SUCCESS;
        }
        // Refused before anything is delivered: it also keeps /r from ever being set to answer itself.
        if (recipient.get().getUniqueId().equals(sender.getUniqueId())) {
            user(sender).reply(ProxyMessages.MESSAGES.chat().msg().self(), Feedback.REFUSED, Tone.WARN);
            return Command.SINGLE_SUCCESS;
        }
        deliverSafely(sender, recipient.get(), text, "/" + name);
        return Command.SINGLE_SUCCESS;
    }

    private int runReply(final CommandContext<CommandSource> context) {
        final Player sender = sender(context);
        if (sender == null) {
            return Command.SINGLE_SUCCESS;
        }
        final String text = StringArgumentType.getString(context, MESSAGE);
        final UUID partner = partners.get(sender.getUniqueId());
        if (partner == null) {
            // Distinct from playerOffline: "nobody has written to you" means type their name instead.
            user(sender).reply(ProxyMessages.MESSAGES.chat().noPartner(), Feedback.REFUSED, Tone.WARN);
            return Command.SINGLE_SUCCESS;
        }
        final Optional<Player> recipient = proxy.getPlayer(partner);
        if (recipient.isEmpty()) {
            // The partner is left standing rather than removed: they may come back.
            user(sender).reply(MESSAGES.command().playerOffline(), Feedback.REFUSED, Tone.WARN);
            return Command.SINGLE_SUCCESS;
        }
        deliverSafely(sender, recipient.get(), text, "/r");
        return Command.SINGLE_SUCCESS;
    }

    private void deliverSafely(final Player from, final Player to, final String text, final String what) {
        try {
            deliver(from, to, text);
        } catch (final RuntimeException failure) {
            // Never with the text in it: a delivery failure is worth logging, what somebody wrote is not.
            logger.warn("{} could not be delivered", what, failure);
            user(from).reply(ProxyMessages.MESSAGES.chat().failed(), Feedback.REFUSED, Tone.BAD);
        }
    }

    /** Both halves of one message, and the reply partner on both sides. */
    private void deliver(final Player sender, final Player recipient, final String text) {
        sender.sendMessage(line(Half.SENT, localeOf(sender), recipient, text));
        recipient.sendMessage(line(Half.RECEIVED, localeOf(recipient), sender, text));
        remember(sender.getUniqueId(), recipient.getUniqueId());
    }

    /** Which copy of a message a line is. */
    enum Half {
        /** The copy the sender keeps. */
        SENT,
        /** The copy that arrives. */
        RECEIVED
    }

    /**
     * One half of a message, drawn.
     *
     * @param half   which of the two copies
     * @param reader the language of the side this line is for, which is never necessarily the
     *               language of the side it is about - the flag is the other one's
     * @param about  the other person: their name, their flag and their admin tag
     */
    Component line(final Half half, final Locale reader, final Player about, final String text) {
        final String flag = Glyphs.flagFor(localeOf(about));
        // A component and never a substituted string, so the text never reaches the MiniMessage parser.
        final Component message = Component.text(text);
        final ProxyMessages.Chat.Msg msg = ProxyMessages.MESSAGES.chat().msg();
        return MessageRenderer.of(messages)
                .format(
                        reader,
                        switch (half) {
                            case SENT ->
                                msg.sent(flag, new PlayerContext(about.getUsername()), adminTag(about), message);
                            case RECEIVED ->
                                msg.received(flag, new PlayerContext(about.getUsername()), adminTag(about), message);
                        });
    }

    /**
     * Who these two would answer with {@code /r}, from now on.
     *
     * Both directions, which is what makes an answer possible without either of them having typed
     * a name.
     */
    void remember(final UUID one, final UUID other) {
        partners.put(one, other);
        partners.put(other, one);
    }

    /**
     * Drops both directions of every conversation this player was in.
     *
     * Both, and not only their own entry: leaving somebody pointed at a UUID that has gone would
     * make their next {@code /r} say "they are not here" forever rather than "nobody has written to
     * you".
     */
    void forget(final UUID gone) {
        partners.remove(gone);
        partners.values().removeIf(gone::equals);
    }

    /**
     * The console is refused: these carry {@code Surface.GAME} and nothing else.
     *
     * A line from the console would arrive signed by nobody, with no session there to hold a reply partner.
     *
     * @return the player who typed it, or {@code null} when the source is not one
     */
    private @Nullable Player sender(final CommandContext<CommandSource> context) {
        if (context.getSource() instanceof Player player) {
            return player;
        }
        new ConsoleUser(messages, context.getSource())
                .reply(MESSAGES.command().notFromConsole(), Feedback.REFUSED, Tone.BAD);
        return null;
    }

    /**
     * The same two lines {@code VelocityCommands} printed for an incomplete command.
     *
     * What to type, and what the command is for.
     *
     * The usage string is written out here rather than derived from a {@code Declaration}.
     * {@code PrivateMessagesTest} parses each tree and asserts the two agree, so the derivation is
     * replaced by a check rather than by trust.
     */
    private int usage(final CommandContext<CommandSource> context, final String usage, final MessageRef describe) {
        final NordtalUser who = context.getSource() instanceof Player player
                ? user(player)
                : new ConsoleUser(messages, context.getSource());
        who.reply(MESSAGES.command().help().usage(usage), Feedback.REFUSED, Tone.NEUTRAL);
        who.reply(MESSAGES.command().help().what(who.phrase(describe)), Tone.MUTED);
        return Command.SINGLE_SUCCESS;
    }

    private VelocityUser user(final Player player) {
        return new VelocityUser(player, roster, messages, colours);
    }

    /**
     * The admin tag with the space in front of it, or nothing at all.
     *
     * Substituted as a {@code {admin}} parameter rather than composed here, so the bundle decides
     * where in the line it sits. It is a private-use code point out of {@code Glyphs} and never
     * written into a {@code .properties} file - the rule this repository has for every glyph.
     */
    private String adminTag(final Player player) {
        return roster.isAdmin(player.getUniqueId()) ? " " + Glyphs.TAG_ADMIN : "";
    }

    private Locale localeOf(final Player player) {
        return roster.localeOf(player.getUniqueId());
    }

    /** Drops both directions of a conversation the moment one side leaves. */
    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    /** @return who this player would answer with {@code /r}, for a test */
    Optional<UUID> partnerOf(final UUID player) {
        return Optional.ofNullable(partners.get(player));
    }
}
