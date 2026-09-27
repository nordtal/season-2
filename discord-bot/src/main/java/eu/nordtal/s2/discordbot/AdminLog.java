package eu.nordtal.s2.discordbot;

import eu.nordtal.s2.discordbot.config.AccessSpec;
import eu.nordtal.s2.discordbot.config.Configured;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/**
 * Writes every admin action to {@code audit_log} and, when a human is needed, to the admin channel.
 *
 * {@link #alert(String)} mentions the admin role for anything somebody must act on; {@link #note(String)} does not.
 */
@Slf4j
public final class AdminLog {

    private final JDA jda;
    private final AccessSpec config;
    private final AuditDao dao;

    public AdminLog(final JDA jda, final AccessSpec config, final Jdbi jdbi) {
        this.jda = jda;
        this.config = config;
        this.dao = jdbi.onDemand(AuditDao.class);
    }

    /** Posts a line somebody must act on, mentioning the admin role when one is configured. */
    public void alert(final String text) {
        post(
                Configured.isSet(config.roles().adminPing())
                        ? "<@&" + config.roles().adminPing() + "> " + text
                        : text);
    }

    /** Posts a line to be read later, without a mention. */
    public void note(final String text) {
        post(text);
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

    private void post(final String text) {
        final MessageChannel channel = channel();
        if (channel == null) {
            // At warn: with no admin channel this is the only place the message exists.
            log.warn("No admin channel, so this was not posted to Discord: {}", text);
            return;
        }
        channel.sendMessage(text)
                .queue(success -> {}, failure -> log.error("Could not write to the admin channel: {}", text, failure));
    }
}
