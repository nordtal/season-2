package eu.nordtal.season.database.guild;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** Stores the channels of the guild. discord-bot writes the whole list, steward reads it for its channel pickers. */
public interface GuildChannels {

    /** Returns the list that uses {@code dataSource} and never closes it. */
    static GuildChannels using(final DataSource dataSource) {
        return new JdbiGuildChannels(dataSource);
    }

    /** Replaces the list of {@code guildId} with {@code channels}, in the guild's order. */
    void publish(String guildId, List<GuildChannel> channels);

    /** Returns the list that the bot published last for {@code guildId}, or nothing if it never did. */
    Optional<List<GuildChannel>> channels(String guildId);
}
