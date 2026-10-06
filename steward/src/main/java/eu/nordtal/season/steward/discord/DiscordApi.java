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

/**
 * The route over the guild's channels as discord-bot last published them, so steward never holds the bot's token.
 *
 * It never fails the page: every failure is a {@code 200} with {@code available: false} and a reason.
 */
public final class DiscordApi {

    private static final StewardTexts.Steward.Said SAID =
            StewardTexts.TEXTS.steward().said();

    private final WebSpec.DiscordSpec config;
    private final @Nullable GuildChannels channels;

    /** @param channels the list discord-bot publishes, absent when this instance has no database */
    public DiscordApi(final WebSpec.DiscordSpec config, final @Nullable GuildChannels channels) {
        this.config = config;
        this.channels = channels;
    }

    public void channels(final Context ctx) {
        ctx.json(guild());
    }

    /** What the guild is made of as the bot last saw it, or why there is no list. */
    Guild guild() {
        final String guildId = config.guildId();
        if (guildId.isBlank()) {
            return new Guild(false, SAID.noGuildId(), List.of());
        }
        final Optional<List<GuildChannel>> published = channels == null ? Optional.empty() : channels.channels(guildId);
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
     * What the guild is made of, or why that could not be answered; without it an id can still be typed.
     *
     * @param reason why there is no list, absent while there is one
     */
    public record Guild(boolean available, @Nullable MessageRef reason, List<Pick> entries) {}

    /**
     * One channel as a picker offers it, in the guild's own order.
     *
     * @param type Discord's channel type, so a category groups apart from its channels
     */
    public record Pick(String id, String name, int type) {}
}
