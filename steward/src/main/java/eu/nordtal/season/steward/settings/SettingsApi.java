package eu.nordtal.season.steward.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.inbox.SettingsChange;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Serves the setting groups of every process and saves changed values as rows.
 * The running server of the group tries a change first. It takes a change at once when its group is live, else at
 * its next start.
 */
public final class SettingsApi {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private final SettingStore store;
    private final AgentClient agent;
    private final @Nullable MessageOverrideStore overrides;
    private final ServerTrial trial;

    public SettingsApi(
            final SettingStore store,
            final AgentClient agent,
            final @Nullable MessageOverrideStore overrides,
            final ServerTrial trial) {
        this.store = store;
        this.agent = agent;
        this.overrides = overrides;
        this.trial = trial;
    }

    public void list(final Context ctx) {
        ctx.json(store.groups().stream().map(SettingsDocument::describe).toList());
    }

    public java.util.List<Object> read() {
        final java.util.List<SettingStore.Group> groups = store.groups();
        final java.util.List<String> services =
                groups.stream().map(SettingStore.Group::service).distinct().toList();
        return java.util.List.of(groups.stream().map(SettingsDocument::describe).toList(), store.overrides(services));
    }

    public void one(final Context ctx) {
        ctx.json(documentOf(groupOf(ctx)).document(null));
    }

    /** Writes the changed values as rows when the revision matches. The response is the group, with {@code reload}
     * saying whether the change applies at once. */
    public void save(final Context ctx, final Actor actor) {
        final SettingStore.Group group = groupOf(ctx);
        final JsonObject body = bodyOf(ctx.body());
        final JsonElement revision = body.get("revision");
        final JsonElement changes = body.get("changes");
        if (revision == null || !revision.isJsonPrimitive() || changes == null || !changes.isJsonObject()) {
            throw new BadRequestResponse("a save sends the revision it read and its changes");
        }
        final Map<String, @Nullable String> rows = documentOf(group).rowsFor(changes.getAsJsonObject());
        final ServerTrial.Verdict verdict = trial.ask(group.service(), changeOf(group, rows), actor);
        if (verdict.kind() == ServerTrial.Verdict.Kind.REFUSED) {
            throw new RequestRefused(422, ANSWER.settingsRefused(String.valueOf(verdict.refusal())));
        }
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
        ctx.json(documentOf(now).document(outcomeOf(now, verdict.kind() == ServerTrial.Verdict.Kind.UNANSWERED)));
    }

    private static SettingsChange changeOf(final SettingStore.Group group, final Map<String, @Nullable String> rows) {
        final Map<String, String> values = new LinkedHashMap<>();
        final List<String> resets = new ArrayList<>();
        rows.forEach((path, value) -> {
            if (value == null) {
                resets.add(path);
            } else {
                values.put(path, value);
            }
        });
        return new SettingsChange(group.name(), values, resets);
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

    private static Reloading outcomeOf(final SettingStore.Group group, final boolean unchecked) {
        final StewardTexts.Steward.Said words = StewardTexts.TEXTS.steward().said();
        final MessageRef said = unchecked
                ? words.settingUnchecked(group.service(), group.live())
                : words.setting(SettingStore.NETWORK.equals(group.service()), group.service(), group.live());
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
