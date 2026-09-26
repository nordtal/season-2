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
import org.slf4j.Logger;

/**
 * The network's own private messages: {@code /msg}, {@code /whisper} and {@code /r}.
 *
 * <h2>Why the proxy owns them</h2>
 * Because it is the only process that can see both people. Vanilla's {@code /tell} is per-server, so
 * a conversation ends the moment one of the two crosses to another backend - and on this network
 * crossing is normal: every login passes the waiting room, and the hunger games and the SMP are
 * different servers. The proxy also already holds what a line has to be drawn from: the language
 * each side reads and their admin flag, both out of {@link LoginRoster}.
 *
 * <h2>Native Brigadier, and why the framework left (season-2-ops/155)</h2>
 * One surface, one target, no confirmation, no admin flag, and arguments that never travel through
 * a database row: none of the four things a {@code Declaration} is worth its cost for. The logic
 * did not change - it moved. What is gone is a declaration in {@code :commands}, an effects
 * interface, the adapter's translation of both, and a catalogue entry for a command no other
 * surface can reach.
 *
 * <h2>Two names and not an alias</h2>
 * {@code /whisper} is built from the same method as {@code /msg}. It is a second registration
 * rather than a Brigadier redirect so that tab completion, the usage line and the command allowlist
 * see two ordinary commands and neither name is a special case.
 *
 * <h2>The message is a component and never a substituted string</h2>
 * {@link MessageRenderer}'s component slot, the same mechanism {@code smp}'s chat format uses for a
 * death message. As a component the player's text never reaches the MiniMessage parser at all, so
 * somebody called {@code <red>} cannot colour a line about themselves.
 *
 * <h2>What a line carries, and what it deliberately does not</h2>
 * The flag and the admin tag, both of which the proxy already holds from the login query. Not the
 * prestige crest and not the aura: those live in the SMP's tables, and fetching them would be a
 * query per message on the process that must not make one. It also keeps a whisper from looking
 * exactly like ordinary chat - a whisper somebody mistakes for public chat is a whisper they answer
 * in public.
 *
 * <h2>Inline, where the framework hopped onto the scheduler</h2>
 * {@code ChatEffects#async} existed because the contract had to hold for a surface that talks to a
 * database. Nothing here does: the roster is a {@code ConcurrentHashMap}, {@code sendMessage} does
 * not block, and the partner map is one too. A scheduled task for that is a hop that only makes the
 * ordering harder to reason about.
 *
 * <h2>Nothing is written down</h2>
 * No log line carries the text, no admin channel is told, no table is touched (Till, 2026-09-08).
 * The only state here is who last spoke to whom, in memory, dropped on disconnect.
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
     * <p>Set by <b>both</b> sides of every message, which is what makes an answer possible without
     * either of them having typed a name. It is held here and dies with the process (Till,
     * 2026-09-08): writing it down would mean a table of who talks to whom, which is a record of
     * exactly the thing this feature is built not to keep. The cost is that a proxy restart makes
     * everybody's next {@code /r} say there is nobody to reply to.</p>
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
        // Offered the same way the adapter offered a PLAYER argument: a map lookup, and never a
        // query - Brigadier evaluates this while building the tree it sends to a client.
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
        // Writing to yourself is refused before anything is delivered (season-2-ingame/24). It also
        // keeps the reply partner from ever being set to the sender - the thing that would make /r
        // answer itself.
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
            // Two different sentences, because they are two different situations and the player can
            // act on the difference: "nobody has written to you" means type their name, "they have
            // gone" means they were there a moment ago.
            user(sender).reply(ProxyMessages.MESSAGES.chat().noPartner(), Feedback.REFUSED, Tone.WARN);
            return Command.SINGLE_SUCCESS;
        }
        final Optional<Player> recipient = proxy.getPlayer(partner);
        if (recipient.isEmpty()) {
            // The partner is left standing rather than removed: they may come back, and telling
            // somebody "they are not here" twice beats telling them "nobody has written to you".
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
            // NEVER WITH THE TEXT IN IT. A delivery that failed is worth knowing about; what
            // somebody wrote is not ours to keep, and a log file is the one place it would survive.
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
     * @param reader the language of the side this line is <em>for</em>, which is never necessarily
     *               the language of the side it is <em>about</em> - the flag is the other one's
     * @param about  the other person: their name, their flag and their admin tag
     */
    Component line(final Half half, final Locale reader, final Player about, final String text) {
        final String flag = Glyphs.flagFor(localeOf(about));
        // A COMPONENT and never a substituted string, so the text never reaches the MiniMessage
        // parser: somebody called <red> cannot colour a line about themselves.
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
     * <p>Both directions, which is what makes an answer possible without either of them having
     * typed a name.</p>
     */
    void remember(final UUID one, final UUID other) {
        partners.put(one, other);
        partners.put(other, one);
    }

    /**
     * Drops both directions of every conversation this player was in.
     *
     * <p>Both, and not only their own entry: leaving somebody pointed at a UUID that has gone would
     * make their next {@code /r} say "they are not here" forever rather than "nobody has written to
     * you".</p>
     */
    void forget(final UUID gone) {
        partners.remove(gone);
        partners.values().removeIf(gone::equals);
    }

    /**
     * The console, answered the way the declaration used to answer it: these carry
     * {@code Surface.GAME} and nothing else, because a line from the console would arrive signed by
     * nobody and there is no session there to hold a reply partner.
     *
     * @return the player who typed it, or {@code null} when the source is not one
     */
    private Player sender(final CommandContext<CommandSource> context) {
        if (context.getSource() instanceof Player player) {
            return player;
        }
        new ConsoleUser(messages, context.getSource())
                .reply(MESSAGES.command().notFromConsole(), Feedback.REFUSED, Tone.BAD);
        return null;
    }

    /**
     * The same two lines {@code VelocityCommands} printed for an incomplete command: what to type,
     * and what the command is for.
     *
     * <p>The usage string is written out here rather than derived from a {@code Declaration}, which
     * is the one thing this move costs. {@code PrivateMessagesTest} parses each tree and asserts the
     * two agree, so the derivation is replaced by a check rather than by trust.</p>
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
     * <p>Substituted as a {@code {admin}} parameter rather than composed here, so the bundle decides
     * where in the line it sits. It is a private-use code point out of {@code Glyphs} and never
     * written into a {@code .properties} file - the rule this repository has for every glyph.</p>
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
