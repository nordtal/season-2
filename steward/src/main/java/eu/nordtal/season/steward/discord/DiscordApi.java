package eu.nordtal.season.steward.discord;

import eu.nordtal.season.database.guild.GuildChannel;
import eu.nordtal.season.database.guild.GuildChannels;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.steward.config.WebSpec;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.Context;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The route for the channels that discord-bot published. A failure answers 200 with {@code available: false}. */
public final class DiscordApi {

    private static final StewardTexts.Steward.Said SAID =
            StewardTexts.TEXTS.steward().said();

    private final WebSpec.DiscordSpec config;
    private final GuildChannels channels;

    /** @param channels the list that discord-bot publishes */
    public DiscordApi(final WebSpec.DiscordSpec config, final GuildChannels channels) {
        this.config = config;
        this.channels = channels;
    }

    public void channels(final Context ctx) {
        ctx.json(guild());
    }

    Guild guild() {
        final String guildId = config.guildId();
        if (guildId.isBlank()) {
            return new Guild(false, SAID.noGuildId(), List.of());
        }
        final Optional<List<GuildChannel>> published = channels.channels(guildId);
        return published
                .map(list -> new Guild(
                        true,
                        null,
                        list.stream()
                                .map(channel -> new Pick(channel.id(), channel.name(), channel.type()))
                                .toList()))
                .orElseGet(() -> new Guild(false, SAID.channelsNotPublished(), List.of()));
    }

    /**
     * The channels of the guild.
     *
     * @param reason the reason for a missing list, absent while there is a list
     */
    public record Guild(boolean available, @Nullable MessageRef reason, List<Pick> entries) {}

    /**
     * One channel of the picker, in guild order.
     *
     * @param type the Discord channel type
     */
    public record Pick(String id, String name, int type) {}
}
