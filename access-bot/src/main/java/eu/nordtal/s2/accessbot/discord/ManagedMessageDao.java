package eu.nordtal.s2.accessbot.discord;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Optional;

/**
 * Which message the bot last posted for which managed message kind.
 * <p>
 * This is the whole reason {@code managed_message} exists: without a stored id, "post my message
 * or edit the one that is already there" needs the bot to scan channel history and guess which
 * message is its own, and a wrong guess leaves two copies that nobody can tell apart.
 * </p>
 */
interface ManagedMessageDao {

    @SqlQuery("SELECT message_id FROM managed_message WHERE kind = :kind AND channel_id = :channelId")
    Optional<String> messageIdOf(@Bind("kind") String kind, @Bind("channelId") String channelId);

    @SqlUpdate("""
            INSERT INTO managed_message (kind, channel_id, message_id, updated)
            VALUES (:kind, :channelId, :messageId, now())
            ON CONFLICT (kind)
                DO UPDATE SET channel_id = EXCLUDED.channel_id,
                              message_id = EXCLUDED.message_id,
                              updated = now()
            """)
    void remember(@Bind("kind") String kind,
                  @Bind("channelId") String channelId,
                  @Bind("messageId") String messageId);
}
