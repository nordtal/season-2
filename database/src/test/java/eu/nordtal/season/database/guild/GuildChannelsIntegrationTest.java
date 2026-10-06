package eu.nordtal.season.database.guild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import java.util.List;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.Test;

/** The guild's channels against a real PostgreSQL and the real migrations; skipped without Docker. */
class GuildChannelsIntegrationTest {

    private static final String GUILD = "100000000000000001";

    private static final List<GuildChannel> CHANNELS = List.of(
            new GuildChannel("200000000000000001", "Community", 4),
            new GuildChannel("200000000000000002", "announcements", 0),
            new GuildChannel("200000000000000003", "Lounge", 2));

    @Test
    void theBotReplacesTheListUnderItsOwnLoginAndStewardReadsItInTheGuildsOrder() {
        final TestDatabase database = TestDatabase.fresh();
        final GuildChannels bot = GuildChannels.using(database.dataSourceAs(DatabaseRole.DISCORD_BOT));
        bot.publish(GUILD, List.of(new GuildChannel("200000000000000009", "gone", 0)));
        bot.publish(GUILD, CHANNELS);

        final GuildChannels steward = GuildChannels.using(database.dataSourceAs(DatabaseRole.STEWARD));
        assertEquals(CHANNELS, steward.channels(GUILD).orElseThrow());
        assertTrue(steward.channels("100000000000000002").isEmpty());
    }

    @Test
    void anEmptyGuildIsAListAndNotNothing() {
        final TestDatabase database = TestDatabase.fresh();
        GuildChannels.using(database.dataSourceAs(DatabaseRole.DISCORD_BOT)).publish(GUILD, List.of());

        assertEquals(
                List.of(),
                GuildChannels.using(database.dataSourceAs(DatabaseRole.STEWARD))
                        .channels(GUILD)
                        .orElseThrow());
    }

    @Test
    void stewardReadsTheListAndNeverWritesIt() {
        final GuildChannels steward = GuildChannels.using(TestDatabase.fresh().dataSourceAs(DatabaseRole.STEWARD));

        assertThrows(UnableToExecuteStatementException.class, () -> steward.publish(GUILD, CHANNELS));
    }
}
