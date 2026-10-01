package eu.nordtal.s2.steward.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.setting.SettingStore;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Every process's settings: the groups it published, and the rows an admin changes.
 *
 * A change is a row and a signal; the process takes it at once where the group is live, else at its next start.
 */
public final class SettingsApi {

    private final SettingStore store;

    public SettingsApi(final SettingStore store) {
        this.store = store;
    }

    /** {@code GET /api/setting-groups}: every published group. */
    void list(final Context ctx) {
        ctx.json(store.groups().stream().map(SettingsDocument::describe).toList());
    }

    /** {@code GET /api/setting-groups/{service}/{name}}: one group as the form draws it. */
    void one(final Context ctx) {
        ctx.json(documentOf(groupOf(ctx)).toJson());
    }

    /**
     * {@code PUT /api/setting-groups/{service}/{name}}: writes the changed values as rows, behind the revision read.
     *
     * Answers the group as it now stands, with {@code reload} saying whether it applies at once.
     */
    void save(final Context ctx, final Actor actor) {
        final SettingStore.Group group = groupOf(ctx);
        final JsonObject body = bodyOf(ctx.body());
        final JsonElement revision = body.get("revision");
        final JsonElement changes = body.get("changes");
        if (revision == null || !revision.isJsonPrimitive() || changes == null || !changes.isJsonObject()) {
            throw new BadRequestResponse("a save sends the revision it read and its changes");
        }
        final Map<String, @Nullable String> rows = documentOf(group).rowsFor(changes.getAsJsonObject());
        final boolean written = store.change(
                group.service(),
                group.name(),
                rows,
                actor,
                held -> SettingsDocument.revisionOf(held).equals(revision.getAsString()));
        if (!written) {
            throw new ConflictResponse(group.service() + "/" + group.name() + " was changed since it was read");
        }
        final SettingStore.Group now =
                store.group(group.service(), group.name()).orElse(group);
        final Map<String, Object> answer = new LinkedHashMap<>(documentOf(now).toJson());
        answer.put("reload", outcomeOf(now));
        ctx.json(answer);
    }

    private SettingStore.Group groupOf(final Context ctx) {
        final String service = ctx.pathParam("service");
        final String name = ctx.pathParam("name");
        return store.group(service, name)
                .orElseThrow(() -> new NotFoundResponse(service + " has published no settings " + name));
    }

    private SettingsDocument documentOf(final SettingStore.Group group) {
        final List<SettingStore.Value> stored = store.overrides(List.of(group.service())).stream()
                .filter(value -> value.group().equals(group.name()))
                .toList();
        return SettingsDocument.of(group, stored);
    }

    private static Map<String, Object> outcomeOf(final SettingStore.Group group) {
        final String who = SettingStore.NETWORK.equals(group.service()) ? "every server" : group.service();
        return group.live()
                ? Map.of("status", "APPLIED", "message", "Saved, " + who + " takes it at once.")
                : Map.of("status", "RESTART_REQUIRED", "message", "Saved, " + who + " takes it at its next start.");
    }

    private static JsonObject bodyOf(final String body) {
        try {
            final JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (final RuntimeException notJson) {
            // Answered below.
        }
        throw new BadRequestResponse("the body is a JSON object");
    }
}
