package eu.nordtal.s2.discordbot;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.alert.AlertBook;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import eu.nordtal.s2.discordbot.config.Configured;
import java.util.List;
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

    private final JDA jda;
    private final AccessSpec config;
    private final AuditDao dao;
    private final AlertBook alerts;

    public AdminLog(final JDA jda, final AccessSpec config, final Jdbi jdbi, final AlertBook alerts) {
        this.jda = jda;
        this.config = config;
        this.dao = jdbi.onDemand(AuditDao.class);
        this.alerts = alerts;
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
        return send(mentions(alert.mentions()), card(emoji(alert.level()) + " " + alert.title(), alert.detail()));
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

    /** Posts a card to be read later, without a mention. */
    public void note(final String title, final String text) {
        send(null, card(title, text));
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
     * Writes one {@code audit_log} row, and never throws.
     *
     * @param action LINK, UNLINK, GRANT_ACCESS, REVOKE_ACCESS, SETTLE, ...
     * @param actor the admin who caused it, {@code null} when the bot acted on its own
     * @param subject who it is about, {@code null} when it is about nobody in particular
     * @param mcUuid the Minecraft account, for link and unlink
     * @param detail free text for whoever reads the table later
     */
    public void record(
            final String action,
            final @Nullable String actor,
            final @Nullable String subject,
            final @Nullable UUID mcUuid,
            final String detail) {
        try {
            dao.record(action, actor, subject, mcUuid, detail);
        } catch (final RuntimeException exception) {
            // An audit write must never take down the action it audits.
            log.error("Could not write the audit_log row for {} ({})", action, detail, exception);
        }
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
