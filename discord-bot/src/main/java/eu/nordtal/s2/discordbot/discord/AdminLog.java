package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.discordbot.config.AccessSpec;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.jdbi.v3.core.Jdbi;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The admin surface: one row in {@code audit_log} and, when a human is needed, one line in the
 * admin channel.
 *
 * <h2>Two things, deliberately together</h2>
 * Every admin action writes both. Splitting them made it possible - and in season 1, normal - for
 * something to be logged to the console and never surface anywhere a human looks. A failed DM is
 * the example the concept calls out by name.
 *
 * <h2>Mention or not</h2>
 * {@link #alert(String)} mentions the admin role; {@link #note(String)} does not. Everything that
 * needs somebody to do something is an alert - an unmatchable payment, a payment on an expired
 * reference, a DM that bounced, a role that could not be set. Routine records - a link, an admin
 * grant that already happened - are notes. A channel that pings for everything is a channel with
 * notifications turned off.
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

    /** Something needs a human. Mentions the admin role. */
    public void alert(final String text) {
        post("<@&" + config.roles().adminPing() + "> " + text);
    }

    /** Something happened that should be readable later. No mention. */
    public void note(final String text) {
        post(text);
    }

    /**
     * Posts an embed and hands its message id back, so a caller can rewrite it later.
     *
     * <p>The one thing in the admin channel that is not a finished sentence: an update run is drawn
     * when it starts and edited as it works, the same way the asker's own ephemeral message is. It
     * goes through this class rather than reaching for the channel directly because "which channel
     * is the admin channel, and what happens when it is missing" is answered here once.</p>
     *
     * @param embed  what to draw
     * @param sentId called with the message id once Discord has accepted it, on a JDA thread. Not
     *               called at all when the post fails, which is why a caller has to treat "no id
     *               yet" as an ordinary state rather than as an error
     */
    public void post(final MessageEmbed embed, final Consumer<String> sentId) {
        final MessageChannel channel = channel();
        if (channel == null) {
            return;
        }
        channel.sendMessageEmbeds(embed).queue(
                sent -> sentId.accept(sent.getId()),
                failure -> log.error("Could not post an embed to the admin channel", failure));
    }

    /**
     * Rewrites a message this class posted.
     *
     * <p>A failure is logged and nothing else: the message is a drawing of a row that is the real
     * record, and an admin channel that cannot be edited must not be able to stop a run.</p>
     */
    public void edit(final String messageId, final MessageEmbed embed) {
        final MessageChannel channel = channel();
        if (channel == null) {
            return;
        }
        channel.editMessageEmbedsById(messageId, embed).queue(
                success -> {
                },
                failure -> log.error("Could not edit admin-channel message {}", messageId, failure));
    }

    /**
     * Writes one {@code audit_log} row.
     *
     * @param action  LINK, UNLINK, GRANT_ACCESS, REVOKE_ACCESS, SETTLE, ...
     * @param actor   the admin who caused it, {@code null} when the bot acted on its own
     * @param subject who it is about, {@code null} when it is about nobody in particular
     * @param mcUuid  the Minecraft account, for link and unlink
     * @param detail  free text for whoever reads the table later
     */
    public void record(final String action, final String actor, final String subject,
                       final UUID mcUuid, final String detail) {
        try {
            dao.record(action, actor, subject, mcUuid, detail);
        } catch (final RuntimeException exception) {
            // Never let an audit write take down the thing it is auditing. The action itself has
            // already happened by the time we get here.
            log.error("Could not write the audit_log row for {} ({})", action, detail, exception);
        }
    }

    private MessageChannel channel() {
        final MessageChannel channel =
                jda.getChannelById(MessageChannel.class, config.channels().admin());
        if (channel == null) {
            log.error("Admin channel {} does not exist or the bot cannot see it",
                    config.channels().admin());
        }
        return channel;
    }

    private void post(final String text) {
        final MessageChannel channel = channel();
        if (channel == null) {
            log.error("The message that could not be posted was: {}", text);
            return;
        }
        channel.sendMessage(text).queue(
                success -> {
                },
                failure -> log.error("Could not write to the admin channel: {}", text, failure));
    }
}
