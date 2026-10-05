package eu.nordtal.s2.steward.discord;

import eu.nordtal.s2.messages.MessageRef;
import io.javalin.http.Context;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The route over {@link DiscordDirectory}: the names of the guild's channels.
 *
 * It never fails the page: every failure is a {@code 200} with {@code available: false} and a reason.
 */
public final class DiscordApi {

    private final DiscordDirectory directory;

    public DiscordApi(final DiscordDirectory directory) {
        this.directory = directory;
    }

    public void channels(final Context ctx) {
        final MessageRef unavailable = directory.unavailable();
        if (unavailable != null) {
            ctx.json(new Guild(false, unavailable, List.of()));
            return;
        }
        try {
            ctx.json(new Guild(
                    true,
                    null,
                    directory.channels().stream()
                            .map(entry -> new Pick(entry.id(), entry.name(), entry.type()))
                            .toList()));
        } catch (final DiscordDirectory.DirectoryException failure) {
            ctx.json(new Guild(false, failure.why(), List.of()));
        }
    }

    /**
     * What the guild is made of, or why that could not be answered; without it an id can still be typed.
     *
     * @param reason why the guild could not be asked, absent while it could
     */
    public record Guild(boolean available, @Nullable MessageRef reason, List<Pick> entries) {}

    /**
     * One channel as a picker offers it, in the guild's own order.
     *
     * @param type Discord's channel type, so a category groups apart from its channels
     */
    public record Pick(String id, String name, @Nullable Integer type) {}
}
