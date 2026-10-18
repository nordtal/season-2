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

/** The commands {@code /msg}, {@code /whisper} and {@code /r}. Messages are not logged or stored. */
public final class PrivateMessages {

    /** The name of the recipient argument. */
    static final String PLAYER = "player";

    /** The name of the argument with the text. */
    static final String MESSAGE = "message";

    private final ProxyServer proxy;
    private final LoginRoster roster;
    private final MessageRenderer renderer;
    private final Supplier<ToneColours> colours;
    private final Logger logger;

    /** The last conversation partner of each player, for {@code /r}. */
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

    /** Returns the three commands. */
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
            user(sender).reply(ProxyMessages.MESSAGES.chat().noPartner(), Feedback.REFUSED, Tone.WARN);
            return Command.SINGLE_SUCCESS;
        }
        final Optional<Player> recipient = proxy.getPlayer(partner);
        if (recipient.isEmpty()) {
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
            logger.warn("{} could not be delivered", what, failure);
            user(from).reply(ProxyMessages.MESSAGES.chat().failed(), Feedback.REFUSED, Tone.BAD);
        }
    }

    /** Sends the message to both players and sets them as each other's reply partner. */
    private void deliver(final Player sender, final Player recipient, final String text) {
        sender.sendMessage(line(Half.SENT, localeOf(sender), recipient, text));
        recipient.sendMessage(line(Half.RECEIVED, localeOf(recipient), sender, text));
        remember(sender.getUniqueId(), recipient.getUniqueId());
    }

    /** The two copies of a message. */
    enum Half {
        /** The copy of the sender. */
        SENT,
        /** The copy of the recipient. */
        RECEIVED
    }

    /**
     * Draws a copy of the message.
     *
     * @param reader the language of the player who reads the copy
     * @param about the other player, whose name, flag and admin tag the copy shows
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

    /** Sets the two players as each other's {@code /r} partner. */
    void remember(final UUID one, final UUID other) {
        partners.put(one, other);
        partners.put(other, one);
    }

    /** Deletes the partner entries of the player and the entries that name the player. */
    void forget(final UUID gone) {
        partners.remove(gone);
        partners.values().removeIf(gone::equals);
    }

    /** Returns the sending player, or {@code null} for the console. */
    private @Nullable Player sender(final CommandContext<CommandSource> context) {
        if (context.getSource() instanceof Player player) {
            return player;
        }
        context.getSource()
                .sendMessage(renderer.format(Locale.ENGLISH, MESSAGES.command().notFromConsole()));
        return null;
    }

    /** Prints the usage and the description of an incomplete command. */
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

    /** Returns the admin tag with a leading space, or an empty string. */
    private String adminTag(final Player player) {
        return roster.isAdmin(player.getUniqueId()) ? " " + Glyphs.TAG_ADMIN : "";
    }

    private Locale localeOf(final Player player) {
        return roster.localeOf(player.getUniqueId());
    }

    /** Deletes the partner entries of the disconnected player. */
    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    /** Returns the reply partner of the player. */
    Optional<UUID> partnerOf(final UUID player) {
        return Optional.ofNullable(partners.get(player));
    }
}
