package eu.nordtal.s2.steward.ui.discord;

import io.javalin.http.Context;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * Two routes over {@link DiscordDirectory}: what the guild's roles are called, and its channels.
 *
 * <p><b>Neither of them ever fails the page.</b> A configuration editor whose pickers cannot be
 * filled is still a configuration editor - the ids can be typed - so an unreachable Discord, a
 * missing token and a rate limit all come back as {@code 200} with {@code available: false} and a
 * sentence saying which of the three it was. That is the same shape {@code /api/deployer} uses, and
 * for the same reason: a red box on a page about something else is noise, and a 500 in the console
 * is a bug report about a thing that is working as designed.</p>
 */
public final class DiscordApi {

    private final DiscordDirectory directory;

    public DiscordApi(final @NotNull DiscordDirectory directory) {
        this.directory = directory;
    }

    /** {@code GET /api/discord/roles} */
    public void roles(final @NotNull Context ctx) {
        answer(ctx, directory::roles);
    }

    /** {@code GET /api/discord/channels} */
    public void channels(final @NotNull Context ctx) {
        answer(ctx, directory::channels);
    }

    private void answer(final Context ctx, final Supplier lookup) {
        final String unavailable = directory.unavailable();
        if (unavailable != null) {
            ctx.json(Map.of("available", false, "reason", unavailable, "entries", List.of()));
            return;
        }
        try {
            ctx.json(Map.of(
                    "available",
                    true,
                    "entries",
                    lookup.get().stream().map(DiscordApi::describe).toList()));
        } catch (final DiscordDirectory.DirectoryException failure) {
            ctx.json(Map.of("available", false, "reason", failure.getMessage(), "entries", List.of()));
        }
    }

    private static Map<String, Object> describe(final DiscordDirectory.Entry entry) {
        // LinkedHashMap rather than Map.of: `type` is null for a role, and Map.of refuses a null
        // value with a NullPointerException that says nothing about which key it was.
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", entry.id());
        row.put("name", entry.name());
        row.put("type", entry.type());
        return row;
    }

    @FunctionalInterface
    private interface Supplier {
        List<DiscordDirectory.Entry> get();
    }
}
