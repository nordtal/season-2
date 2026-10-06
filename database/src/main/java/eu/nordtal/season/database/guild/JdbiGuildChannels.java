package eu.nordtal.season.database.guild;

import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.Jdbis;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;

/** The only implementation of {@link GuildChannels}; it borrows the pool it is given and owns nothing. */
final class JdbiGuildChannels implements GuildChannels {

    private final Jdbi jdbi;

    JdbiGuildChannels(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public void publish(final String guildId, final List<GuildChannel> channels) {
        final String list = Json.encode(List.copyOf(channels));
        // The list is bound twice rather than read from excluded, which would need the bot to read every column.
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO guild_channels (guild_id, channels, published)
                        VALUES (:guild, CAST(:channels AS jsonb), now())
                        ON CONFLICT (guild_id) DO UPDATE SET channels = CAST(:channels AS jsonb), published = now()""")
                .bind("guild", guildId)
                .bind("channels", list)
                .execute());
    }

    @Override
    public Optional<List<GuildChannel>> channels(final String guildId) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT channels::text FROM guild_channels WHERE guild_id = :guild")
                .bind("guild", guildId)
                .mapTo(String.class)
                .findOne()
                .map(list -> List.of(Json.decode(list, GuildChannel[].class))));
    }
}
