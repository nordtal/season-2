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

    /** Every group and every stored override, which together change whenever a page of settings would. */
    java.util.List<Object> read() {
        final java.util.List<SettingStore.Group> groups = store.groups();
        final java.util.List<String> services =
                groups.stream().map(SettingStore.Group::service).distinct().toList();
        return java.util.List.of(groups.stream().map(SettingsDocument::describe).toList(), store.overrides(services));
    }

    /** {@code GET /api/setting-groups/{service}/{name}}: one group as the form draws it. */
    void one(final Context ctx) {
        ctx.json(documentOf(groupOf(ctx)).document(null));
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
        ctx.json(documentOf(now).document(outcomeOf(now)));
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

    private static Reloading outcomeOf(final SettingStore.Group group) {
        final String who = SettingStore.NETWORK.equals(group.service()) ? "every server" : group.service();
        return group.live()
                ? Reloading.applied("Saved, " + who + " takes it at once.")
                : Reloading.restartRequired("Saved, " + who + " takes it at its next start.");
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
