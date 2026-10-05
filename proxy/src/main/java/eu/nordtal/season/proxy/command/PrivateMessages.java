package eu.nordtal.season.proxy.command;

import static eu.nordtal.season.proxy.ProxyMessages.MESSAGES;

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
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messagerendering.ToneColours;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.LanguageFlags;
import eu.nordtal.season.proxy.ProxyMessages;
import eu.nordtal.season.proxy.gate.LoginRoster;
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
 * The proxy sees both sides across backends. Nothing is logged or stored; reply partners live in memory only.
 */
public final class PrivateMessages {

    /** The recipient argument's name, which tab completion offers against. */
    static final String PLAYER = "player";

    /** Everything after the name, taken whole. */
    static final String MESSAGE = "message";

    private final ProxyServer proxy;
    private final LoginRoster roster;
    private final MessageRenderer renderer;
    private final Supplier<ToneColours> colours;
    private final Logger logger;

    /** Who each connected player last exchanged a private message with, for {@code /r}; lost on restart by design. */
    private final ConcurrentHashMap<UUID, UUID> partners = new ConcurrentHashMap<>();

    public PrivateMessages(
            final ProxyServer proxy,
            final LoginRoster roster,
            final MessageRenderer renderer,
            final Supplier<ToneColours> colours,
            final Logger logger) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
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
        // A map lookup and never a query: Brigadier evaluates this while building the client's tree.
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

    /** Delivers both halves of one message and sets the reply partner on both sides. */
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
     * Draws one half of a message.
     *
     * @param half which of the two copies
     * @param reader the language of the side this line is for
     * @param about the other person, whose name, flag and admin tag are shown
     */
    Component line(final Half half, final Locale reader, final Player about, final String text) {
        final String flag = LanguageFlags.of(localeOf(about));
        final ProxyMessages.Chat.Msg msg = ProxyMessages.MESSAGES.chat().msg();
        return renderer.format(
                reader,
                switch (half) {
                    case SENT ->
                        msg.sent(
                                flag,
                                PlayerContext.of(PlayerId.of(about.getUniqueId()), about.getUsername()),
                                adminTag(about),
                                text);
                    case RECEIVED ->
                        msg.received(
                                flag,
                                PlayerContext.of(PlayerId.of(about.getUniqueId()), about.getUsername()),
                                adminTag(about),
                                text);
                });
    }

    /** Sets these two as each other's {@code /r} partner. */
    void remember(final UUID one, final UUID other) {
        partners.put(one, other);
        partners.put(other, one);
    }

    /** Drops both directions of every conversation this player was in. */
    void forget(final UUID gone) {
        partners.remove(gone);
        partners.values().removeIf(gone::equals);
    }

    /** Returns the player who typed it, or {@code null} for the console, which these commands refuse. */
    private @Nullable Player sender(final CommandContext<CommandSource> context) {
        if (context.getSource() instanceof Player player) {
            return player;
        }
        context.getSource()
                .sendMessage(renderer.format(Locale.ENGLISH, MESSAGES.command().notFromConsole()));
        return null;
    }

    /**
     * Prints the usage line and the description for an incomplete command.
     *
     * {@code PrivateMessagesTest} checks the hand-written usage against each tree.
     */
    private int usage(final CommandContext<CommandSource> context, final String usage, final MessageRef describe) {
        if (!(context.getSource() instanceof Player player)) {
            context.getSource()
                    .sendMessage(renderer.format(
                            Locale.ENGLISH, MESSAGES.command().help().usage(usage)));
            return Command.SINGLE_SUCCESS;
        }
        final VelocityUser who = user(player);
        who.reply(MESSAGES.command().help().usage(usage), Feedback.REFUSED, Tone.NEUTRAL);
        who.reply(MESSAGES.command().help().what(who.phrase(describe)), Tone.MUTED);
        return Command.SINGLE_SUCCESS;
    }

    private VelocityUser user(final Player player) {
        return new VelocityUser(player, roster, renderer, colours);
    }

    /** The admin tag with a leading space, or nothing, substituted as {@code {admin}}. */
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

    /** Returns who this player would answer with {@code /r}, for a test. */
    Optional<UUID> partnerOf(final UUID player) {
        return Optional.ofNullable(partners.get(player));
    }
}
