package eu.nordtal.season.database.guild;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The guild's channels: discord-bot writes the whole list, steward reads it for its channel pickers.
 *
 * Steward thus names a channel without the bot's token, and still does while the bot is down.
 */
public interface GuildChannels {

    /** Returns the list over {@code dataSource}, which it borrows and never closes. */
    static GuildChannels using(final DataSource dataSource) {
        return new JdbiGuildChannels(dataSource);
    }

    /** Replaces the list of {@code guildId} with {@code channels}, in the guild's own order. */
    void publish(String guildId, List<GuildChannel> channels);

    /** The list the bot published last for {@code guildId}, or nothing when it never has. */
    Optional<List<GuildChannel>> channels(String guildId);
}
