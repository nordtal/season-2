package eu.nordtal.season.discordbot;

import java.util.Optional;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * Which message the bot last posted for each managed message kind, so a restart edits it instead of posting again.
 *
 * Any feature with a message per kind uses it, since {@code managed_message.kind} is unconstrained.
 */
public interface ManagedMessageDao {

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
    void remember(@Bind("kind") String kind, @Bind("channelId") String channelId, @Bind("messageId") String messageId);
}
