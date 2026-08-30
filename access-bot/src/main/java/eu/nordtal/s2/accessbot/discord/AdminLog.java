package eu.nordtal.s2.accessbot.discord;

import eu.nordtal.s2.accessbot.config.AccessSpec;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.jdbi.v3.core.Jdbi;

import java.util.UUID;

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

    private void post(final String text) {
        final MessageChannel channel = jda.getChannelById(MessageChannel.class, config.channels().admin());
        if (channel == null) {
            log.error("Admin channel {} does not exist or the bot cannot see it. The message was: {}",
                    config.channels().admin(), text);
            return;
        }
        channel.sendMessage(text).queue(
                success -> {
                },
                failure -> log.error("Could not write to the admin channel: {}", text, failure));
    }
}
