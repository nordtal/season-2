package eu.nordtal.season.steward.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Every process's settings: the groups it published, and the rows an admin changes.
 *
 * A change is a row and a signal; the process takes it at once where the group is live, else at its next start.
 */
public final class SettingsApi {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private final SettingStore store;
    private final AgentClient agent;
    private final @Nullable MessageOverrideStore overrides;

    /**
     * @param agent where the jars are read whose texts name a group's choices
     * @param overrides the admins' changes to those texts, or {@code null} without a database
     */
    public SettingsApi(
            final SettingStore store, final AgentClient agent, final @Nullable MessageOverrideStore overrides) {
        this.store = store;
        this.agent = agent;
        this.overrides = overrides;
    }

    /** {@code GET /api/setting-groups}: every published group. */
    public void list(final Context ctx) {
        ctx.json(store.groups().stream().map(SettingsDocument::describe).toList());
    }

    /** Every group and every stored override, which together change whenever a page of settings would. */
    public java.util.List<Object> read() {
        final java.util.List<SettingStore.Group> groups = store.groups();
        final java.util.List<String> services =
                groups.stream().map(SettingStore.Group::service).distinct().toList();
        return java.util.List.of(groups.stream().map(SettingsDocument::describe).toList(), store.overrides(services));
    }

    /** {@code GET /api/setting-groups/{service}/{name}}: one group as the form draws it. */
    public void one(final Context ctx) {
        ctx.json(documentOf(groupOf(ctx)).document(null));
    }

    /**
     * {@code PUT /api/setting-groups/{service}/{name}}: writes the changed values as rows, behind the revision read.
     *
     * Answers the group as it now stands, with {@code reload} saying whether it applies at once.
     */
    public void save(final Context ctx, final Actor actor) {
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
            throw new RequestRefused(409, ANSWER.settingsChanged(group.service(), group.name()));
        }
        final SettingStore.Group now =
                store.group(group.service(), group.name()).orElse(group);
        ctx.json(documentOf(now).document(outcomeOf(now)));
    }

    private SettingStore.Group groupOf(final Context ctx) {
        final String service = ctx.pathParam("service");
        final String name = ctx.pathParam("name");
        return store.group(service, name).orElseThrow(() -> new RequestRefused(404, ANSWER.noSettings(service, name)));
    }

    private SettingsDocument documentOf(final SettingStore.Group group) {
        final List<SettingStore.Value> stored = store.overrides(List.of(group.service())).stream()
                .filter(value -> value.group().equals(group.name()))
                .toList();
        return SettingsDocument.of(group, stored, PluginNames.of(agent, overrides, group.service()));
    }

    private static Reloading outcomeOf(final SettingStore.Group group) {
        final MessageRef said = StewardTexts.TEXTS
                .steward()
                .said()
                .setting(SettingStore.NETWORK.equals(group.service()), group.service(), group.live());
        return group.live() ? Reloading.applied(said) : Reloading.restartRequired(said);
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
