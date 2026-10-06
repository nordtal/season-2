package eu.nordtal.season.steward.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.guild.GuildChannel;
import eu.nordtal.season.database.guild.GuildChannels;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.steward.config.WebSpec;
import eu.nordtal.season.steward.texts.WebTexts;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The channel pickers' list: what discord-bot published for steward's guild, or why there is none. */
class DiscordApiTest {

    private static final Messages TEXTS = WebTexts.load().messages();

    private static final String GUILD = "100000000000000001";

    /** A store holding what the bot published, by guild. */
    private static GuildChannels published(final Map<String, List<GuildChannel>> lists) {
        return new GuildChannels() {
            @Override
            public void publish(final String guildId, final List<GuildChannel> channels) {
                throw new UnsupportedOperationException("steward never publishes the list");
            }

            @Override
            public Optional<List<GuildChannel>> channels(final String guildId) {
                return Optional.ofNullable(lists.get(guildId));
            }
        };
    }

    private static WebSpec.DiscordSpec guild(final String id) {
        return new WebSpec.DiscordSpec() {
            @Override
            public String guildId() {
                return id;
            }
        };
    }

    @Test
    void theListIsTheOneTheBotPublishedForStewardsGuildInItsOrder() {
        final DiscordApi.Guild answer = new DiscordApi(
                        guild(GUILD),
                        published(Map.of(
                                GUILD,
                                List.of(
                                        new GuildChannel("30", "Community", 4),
                                        new GuildChannel("10", "talk", 0),
                                        new GuildChannel("20", "Lounge", 2)),
                                "100000000000000002",
                                List.of(new GuildChannel("40", "elsewhere", 0)))))
                .guild();

        assertTrue(answer.available());
        assertNull(answer.reason());
        assertEquals(
                List.of(
                        new DiscordApi.Pick("30", "Community", 4),
                        new DiscordApi.Pick("10", "talk", 0),
                        new DiscordApi.Pick("20", "Lounge", 2)),
                answer.entries());
    }

    @Test
    void aGuildTheBotHasNotPublishedSaysSoAndLeavesTheIdsToBeTyped() {
        final DiscordApi.Guild answer = new DiscordApi(guild(GUILD), published(Map.of())).guild();

        assertFalse(answer.available());
        final String reason = english(answer.reason());
        assertTrue(reason.contains("discord-bot has not published"), reason);
    }

    @Test
    void withoutADatabaseItSaysTheListIsNotPublished() {
        final String reason = english(new DiscordApi(guild(GUILD), null).guild().reason());

        assertTrue(reason.contains("discord-bot has not published"), reason);
    }

    @Test
    void withoutAGuildItSaysWhichValueIsMissing() {
        final String reason =
                english(new DiscordApi(guild(""), published(Map.of())).guild().reason());

        assertTrue(reason.contains("discord.guild-id"), reason);
    }

    private static String english(final @Nullable MessageRef message) {
        assertNotNull(message);
        return TEXTS.format(Locales.DEFAULT, message);
    }
}
