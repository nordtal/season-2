package eu.nordtal.s2.discordbot;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.alert.AlertBook;
import eu.nordtal.s2.database.audit.AuditLine;
import eu.nordtal.s2.database.audit.Journal;
import eu.nordtal.s2.database.audit.JournalAction;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import eu.nordtal.s2.discordbot.config.Configured;
import eu.nordtal.s2.messages.MessageRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/**
 * Writes every admin action to {@code audit_log}, posts notes to the admin channel, and raises what needs a human.
 *
 * An {@link #alert} is a row steward routes, so this class never decides who hears of it; {@link #postAlert} draws one.
 */
@Slf4j
public final class AdminLog {

    static final String RAISED_BY = "discord-bot";

    /** The mark of a journal line in the admin channel; an action not listed here is posted with a pencil. */
    private static final Map<JournalAction, String> MARKS = Map.of(
            JournalAction.GRANT_ACCESS, "🎟️",
            JournalAction.REVOKE_ACCESS, "🚫",
            JournalAction.LINK, "🔗",
            JournalAction.UNLINK, "✂️",
            JournalAction.SET_PLAYTIME, "⏱️");

    private final JDA jda;
    private final AccessSpec config;
    private final Jdbi jdbi;
    private final AlertBook alerts;
    private final DiscordRenderer texts;

    public AdminLog(
            final JDA jda,
            final AccessSpec config,
            final Jdbi jdbi,
            final AlertBook alerts,
            final DiscordRenderer texts) {
        this.jda = jda;
        this.config = config;
        this.jdbi = jdbi;
        this.alerts = alerts;
        this.texts = texts;
    }

    /** Raises something an admin must act on; steward routes it to push and back here, and this never throws. */
    public void alert(final Alert alert) {
        raise(alerts, alert);
    }

    static void raise(final AlertBook book, final Alert alert) {
        try {
            book.raise(alert, RAISED_BY);
        } catch (final RuntimeException exception) {
            // With the database gone, this line is the only place the alert exists.
            log.error("Could not raise the alert {}: {}", alert.title(), alert.detail(), exception);
        }
    }

    /** Posts an alert steward routed here, and answers whether there was an admin channel to post it to. */
    public boolean postAlert(final BotRequest.PostAlert alert) {
        return send(mentions(alert.mentions()), card(texts, alert));
    }

    /** Draws an alert: its level's mark and title, the lines below it, and the link to its page. */
    static MessageEmbed card(final DiscordRenderer texts, final BotRequest.PostAlert alert) {
        final List<String> lines = new ArrayList<>();
        alert.detail().forEach(line -> lines.add(texts.format(Locales.DEFAULT, line)));
        if (alert.link() != null) {
            lines.add(alert.link());
        }
        return card(
                emoji(alert.level()) + " " + texts.format(Locales.DEFAULT, alert.title()), String.join("\n", lines));
    }

    /** The mentions of the admins who want an alert in Discord, or {@code null} when nobody is to be pinged. */
    static @Nullable String mentions(final List<DiscordId> admins) {
        return admins.isEmpty()
                ? null
                : admins.stream().map(admin -> "<@" + admin + ">").collect(Collectors.joining(" "));
    }

    static String emoji(final Alert.Level level) {
        return switch (level) {
            case DOWN -> "🛑";
            case WARN -> "⚠️";
            case OK -> "✅";
        };
    }

    /** Posts a card to be read later, without a mention: the mark, the title, and one line below it. */
    public void note(final String mark, final MessageRef title, final MessageRef line) {
        send(null, card(texts, mark, title, line));
    }

    static MessageEmbed card(
            final DiscordRenderer texts, final String mark, final MessageRef title, final MessageRef line) {
        return card(mark + " " + texts.format(Locales.DEFAULT, title), texts.format(Locales.DEFAULT, line));
    }

    /** Draws one admin-log line; a mention stays outside, since a mention inside an embed pings nobody. */
    static MessageEmbed card(final String title, final String text) {
        return Card.of(title).lead(text).build();
    }

    /**
     * Posts an embed that a caller can rewrite later.
     *
     * @param embed what to draw
     * @param sentId called on a JDA thread with the message id, and not at all when the post fails
     */
    public void post(final MessageEmbed embed, final Consumer<String> sentId) {
        final MessageChannel channel = channel();
        if (channel == null) {
            return;
        }
        channel.sendMessageEmbeds(embed)
                .queue(
                        sent -> sentId.accept(sent.getId()),
                        failure -> log.error("Could not post an embed to the admin channel", failure));
    }

    /** Rewrites a message this class posted; a failure is only logged, so it can never stop a run. */
    public void edit(final String messageId, final MessageEmbed embed) {
        final MessageChannel channel = channel();
        if (channel == null) {
            return;
        }
        channel.editMessageEmbedsById(messageId, embed)
                .queue(
                        success -> {},
                        failure -> log.error("Could not edit admin-channel message {}", messageId, failure));
    }

    /**
     * Writes one journal line and posts it to the admin channel, and never throws.
     *
     * The action it records has already happened. The card renders the message the journal stores, so both agree.
     */
    public void record(final AuditLine line) {
        try {
            jdbi.useHandle(handle -> Journal.write(handle, line));
        } catch (final RuntimeException exception) {
            log.error("Could not write the audit_log row for {} {}", line.action(), line.line(), exception);
        }
        send(null, card(texts, line));
    }

    /** Draws a journal line: its action as the title, the line, who did it and whom it concerns. */
    static MessageEmbed card(final DiscordRenderer texts, final AuditLine line) {
        final Card card = Card.of(MARKS.getOrDefault(line.action(), "📝") + " "
                        + texts.format(Locales.DEFAULT, TEXTS.journal().action(line.action())))
                .lead(texts.format(Locales.DEFAULT, line.line()))
                .field(
                        texts.format(Locales.DEFAULT, TEXTS.journal().by()),
                        texts.format(Locales.DEFAULT, TEXTS.journal().who(line.actor())));
        final DiscordId subject = line.subject();
        if (subject != null) {
            card.field(
                    texts.format(Locales.DEFAULT, TEXTS.journal().concerns()),
                    texts.format(Locales.DEFAULT, TEXTS.journal().who(Actor.person(subject))));
        }
        final UUID account = line.mcUuid();
        if (account != null) {
            card.field(texts.format(Locales.DEFAULT, TEXTS.journal().minecraft()), "`" + account + "`");
        }
        return card.build();
    }

    private @Nullable MessageChannel channel() {
        // Unconfigured and unresolvable log differently: no channel picked, or one since removed.
        if (!Configured.isSet(config.channels().admin())) {
            return null;
        }
        final MessageChannel channel =
                jda.getChannelById(MessageChannel.class, config.channels().admin());
        if (channel == null) {
            log.error(
                    "Admin channel {} does not exist or the bot cannot see it",
                    config.channels().admin());
        }
        return channel;
    }

    private boolean send(final @Nullable String mention, final MessageEmbed embed) {
        final MessageChannel channel = channel();
        if (channel == null) {
            // At warn: with no admin channel this is the only place the message exists.
            log.warn(
                    "No admin channel, so this was not posted to Discord: {} {}",
                    embed.getTitle(),
                    embed.getDescription());
            return false;
        }
        (mention == null
                        ? channel.sendMessageEmbeds(embed)
                        : channel.sendMessage(mention).setEmbeds(embed))
                .queue(
                        success -> {},
                        failure -> log.error(
                                "Could not write to the admin channel: {} {}",
                                embed.getTitle(),
                                embed.getDescription(),
                                failure));
        return true;
    }
}
