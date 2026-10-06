package eu.nordtal.season.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.guild.GuildChannel;
import eu.nordtal.season.database.guild.GuildChannels;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** What the bot publishes for Steward's channel pickers, and which channel events make it publish again. */
class ChannelListTest {

    private static final String GUILD = "100000000000000001";

    private final List<List<GuildChannel>> published = new ArrayList<>();

    private final GuildChannels store = new GuildChannels() {
        @Override
        public void publish(final String guildId, final List<GuildChannel> channels) {
            assertEquals(GUILD, guildId);
            published.add(channels);
        }

        @Override
        public Optional<List<GuildChannel>> channels(final String guildId) {
            throw new UnsupportedOperationException("the bot never reads the list back");
        }
    };

    @Test
    void theWholeListIsPublishedInTheGuildsOrderWithDiscordsChannelTypes() {
        final Guild guild = guild(GUILD);
        channels(
                guild,
                List.of(
                        channel(guild, "1", "Community", ChannelType.CATEGORY),
                        channel(guild, "2", "announcements", ChannelType.TEXT),
                        channel(guild, "3", "Lounge", ChannelType.VOICE)));

        list(guild).publish();

        assertEquals(
                List.of(List.of(
                        new GuildChannel("1", "Community", 4),
                        new GuildChannel("2", "announcements", 0),
                        new GuildChannel("3", "Lounge", 2))),
                published);
    }

    @Test
    void aChannelOfTheGuildChangingPublishesAgainAndAThreadOrAnotherGuildDoesNot() {
        final Guild guild = guild(GUILD);
        final Guild other = guild("100000000000000002");
        channels(guild, List.of());
        final ChannelList list = list(guild);
        final JDA jda = jda(guild);

        list.onGenericChannel(
                new ChannelCreateEvent(jda, 0, channel(guild, "4", "thread", ChannelType.GUILD_PUBLIC_THREAD)));
        list.onGenericChannel(new ChannelCreateEvent(jda, 0, channel(other, "5", "elsewhere", ChannelType.TEXT)));
        assertEquals(List.of(), published);

        list.onGenericChannel(new ChannelCreateEvent(jda, 0, channel(guild, "6", "new", ChannelType.TEXT)));
        assertEquals(1, published.size());
    }

    @Test
    void aGuildTheBotCannotSeeOrAFailedWriteLeavesTheLastListAndThrowsNothing() {
        final ChannelList unseen = new ChannelList(jda(null), GUILD, store, Runnable::run);
        unseen.publish();
        assertEquals(List.of(), published);

        final Guild guild = guild(GUILD);
        channels(guild, List.of());
        final GuildChannels failing = new GuildChannels() {
            @Override
            public void publish(final String guildId, final List<GuildChannel> channels) {
                throw new IllegalStateException("the database is down");
            }

            @Override
            public Optional<List<GuildChannel>> channels(final String guildId) {
                return Optional.empty();
            }
        };
        assertDoesNotThrow(() -> new ChannelList(jda(guild), GUILD, failing, Runnable::run).publish());
    }

    private ChannelList list(final Guild guild) {
        return new ChannelList(jda(guild), GUILD, store, Runnable::run);
    }

    /** What each guild's {@code getChannels} answers; JDA's channel type shares its simple name with ours. */
    private final IdentityHashMap<Guild, List<?>> channelsOf = new IdentityHashMap<>();

    private void channels(final Guild guild, final List<?> channels) {
        channelsOf.put(guild, channels);
    }

    private Guild guild(final String id) {
        return (Guild) Proxy.newProxyInstance(
                Guild.class.getClassLoader(),
                new Class<?>[] {Guild.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    // The map compares by identity, so the stand-in needs no equals of its own.
                    case "getChannels" -> channelsOf.get((Guild) proxy);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Discord channel(final Guild guild, final String id, final String name, final ChannelType type) {
        return (Discord) Proxy.newProxyInstance(
                Discord.class.getClassLoader(),
                new Class<?>[] {Discord.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "getIdLong" -> Long.parseLong(id);
                    case "getName" -> name;
                    case "getType" -> type;
                    case "getGuild" -> guild;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static JDA jda(final @Nullable Guild guild) {
        return (JDA) Proxy.newProxyInstance(
                JDA.class.getClassLoader(),
                new Class<?>[] {JDA.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getGuildById" -> guild;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** JDA's guild channel under a name of its own. */
    private interface Discord extends net.dv8tion.jda.api.entities.channel.middleman.GuildChannel {}
}
