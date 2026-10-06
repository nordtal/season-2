package eu.nordtal.season.discordbot.discord;

import eu.nordtal.season.database.guild.GuildChannel;
import eu.nordtal.season.database.guild.GuildChannels;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.channel.GenericChannelEvent;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * Publishes the guild's channels into {@code guild_channels}, so Steward's pickers name them without the bot's token.
 *
 * The whole list is written at start, on every change of a channel and after a new session, one write at a time.
 */
@Slf4j
public final class ChannelList extends ListenerAdapter {

    private final JDA jda;
    private final String guildId;
    private final GuildChannels store;
    private final Executor lane;

    /** @param lane where each write runs, so a slow database never holds up the gateway's events */
    public ChannelList(final JDA jda, final String guildId, final GuildChannels store, final Executor lane) {
        this.jda = Objects.requireNonNull(jda, "jda");
        this.guildId = Objects.requireNonNull(guildId, "guildId");
        this.store = Objects.requireNonNull(store, "store");
        this.lane = Objects.requireNonNull(lane, "lane");
    }

    /** Writes the list the bot sees now, after the writes asked for before it. */
    public void publish() {
        lane.execute(this::write);
    }

    @Override
    public void onGenericChannel(final GenericChannelEvent event) {
        // A thread is no channel a setting names, and Discord's own list of the guild's channels leaves them out.
        if (event.isFromGuild()
                && event.getGuild().getId().equals(guildId)
                && !event.getChannelType().isThread()) {
            publish();
        }
    }

    @Override
    public void onSessionRecreate(final SessionRecreateEvent event) {
        // A new session rebuilds the cache without an event for what changed meanwhile.
        publish();
    }

    private void write() {
        final Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            log.warn("Guild {} is not available, so its channels were not published", guildId);
            return;
        }
        final List<GuildChannel> channels = guild.getChannels().stream()
                .map(channel -> new GuildChannel(
                        channel.getId(), channel.getName(), channel.getType().getId()))
                .toList();
        try {
            store.publish(guildId, channels);
        } catch (final RuntimeException failed) {
            log.warn("The guild's {} channels were not published: {}", channels.size(), failed.getMessage());
        }
    }
}
