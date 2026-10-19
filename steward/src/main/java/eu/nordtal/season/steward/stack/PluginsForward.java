package eu.nordtal.season.steward.stack;

import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.steward.journal.Journaling;
import eu.nordtal.season.steward.settings.Reloading;
import eu.nordtal.season.steward.settings.SettingsDocument;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Forwards the plugins of one server, their search, install and config files to steward-agent with the asker. */
public final class PluginsForward {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();
    private static final StewardTexts.Steward.Said ANSWERED =
            StewardTexts.TEXTS.steward().said();

    private final AgentClient agent;
    private final UpdateDirectory updates;

    public PluginsForward(final AgentClient agent, final UpdateDirectory updates) {
        this.agent = agent;
        this.updates = updates;
    }

    void list(final Context ctx) {
        ctx.json(agent.plugins(service(ctx)));
    }

    void search(final Context ctx) {
        ctx.json(agent.searchPlugins(service(ctx), Objects.requireNonNullElse(ctx.queryParam("q"), "")));
    }

    /** {@code POST /api/services/{name}/plugins} adds a plugin row. */
    AgentWire.PluginAdded add(final Context ctx, final Actor by) {
        final AgentWire.PluginAsk ask = ctx.bodyAsClass(AgentWire.PluginAsk.class);
        if (ask == null) {
            throw new BadRequestResponse("the body is the Modrinth project to install");
        }
        final AgentWire.PluginAdded added = agent.addPlugin(service(ctx), new AgentWire.AddPlugin(ask, by));
        ctx.status(201).json(added);
        return added;
    }

    /** The answer to {@code DELETE /api/services/{name}/plugins/{artifact}}. */
    public record RemovalAsked(long id, UpdateKind kind, String artifact) {}

    /** {@code DELETE /api/services/{name}/plugins/{artifact}} writes a REMOVE_PLUGIN run request. */
    void remove(final Context ctx, final Actor actor) {
        final String artifact = ctx.pathParam("artifact");
        final UpdateRequest written;
        try {
            written = updates.submit(
                    new StewardRequest.RemovePlugin(List.of(service(ctx)), artifact), actor, Duration.ZERO);
        } catch (final IllegalArgumentException malformed) {
            throw new BadRequestResponse(malformed.getMessage());
        }
        Journaling.line(ctx, new JournalDetail.PluginRemoved(written.id(), service(ctx), artifact));
        ctx.status(202).json(new RemovalAsked(written.id(), written.kind(), artifact));
    }

    void files(final Context ctx) {
        ctx.json(agent.pluginFiles(service(ctx)));
    }

    /** A plugin file as the browser opens it: its text and, for a YAML or JSON file that has one, its form. */
    public record PluginFileView(
            AgentWire.PluginFile file,
            String text,
            String revision,
            @Nullable List<SettingsDocument.Entry> entries) {}

    void file(final Context ctx) {
        final AgentWire.PluginFileDocument document = agent.pluginFile(service(ctx), plugin(ctx), path(ctx));
        ctx.json(new PluginFileView(
                document.file(),
                document.text(),
                document.revision(),
                PluginYaml.entries(document.file().path(), document.text())));
    }

    /**
     * The body of a plugin file's save and the revision it was edited from: the whole text, or the form's changes.
     */
    public record PluginFileEdit(
            @Nullable String text,
            String revision,
            @Nullable JsonObject changes) {}

    /** A saved plugin file, which its server reads at its next start. */
    public record PluginFileSaved(AgentWire.PluginFile file, String revision, Reloading reload) {}

    /**
     * {@code PUT /api/services/{name}/plugin-file} writes the file and nothing else.
     *
     * The server reads it when it next starts. A file that changed on disk since {@code revision} is a 409. The form's
     * changes are written into the text as it is now, which keeps every other byte of it.
     */
    PluginFileSaved save(final Context ctx, final Actor by) {
        final PluginFileEdit edit = ctx.bodyAsClass(PluginFileEdit.class);
        // Gson leaves a missing field null whatever the record declares.
        if (edit == null || edit.revision() == null || (edit.text() == null) == (edit.changes() == null)) {
            throw new BadRequestResponse(
                    "the body is the file's text or the form's changes, and the revision it was edited from");
        }
        final String text;
        if (edit.changes() == null) {
            text = Objects.requireNonNull(edit.text());
        } else {
            final AgentWire.PluginFileDocument now = agent.pluginFile(service(ctx), plugin(ctx), path(ctx));
            if (!now.revision().equals(edit.revision())) {
                throw new RequestRefused(409, ANSWER.settingsChanged(service(ctx), plugin(ctx) + "/" + path(ctx)));
            }
            text = PluginYaml.apply(now.file().path(), now.text(), edit.changes());
        }
        final AgentWire.PluginFileSaved written = agent.savePluginFile(
                service(ctx), plugin(ctx), path(ctx), new AgentWire.PluginFileSave(text, edit.revision(), by));
        final PluginFileSaved saved = new PluginFileSaved(
                written.file(),
                written.revision(),
                Reloading.restartRequired(ANSWERED.setting(false, service(ctx), false)));
        ctx.json(saved);
        return saved;
    }

    private static String plugin(final Context ctx) {
        return required(ctx, "plugin");
    }

    private static String path(final Context ctx) {
        return required(ctx, "path");
    }

    private static String required(final Context ctx, final String name) {
        final String value = ctx.queryParam(name);
        if (value == null || value.isBlank()) {
            throw new BadRequestResponse("?" + name + "= names the file");
        }
        return value;
    }

    private static String service(final Context ctx) {
        final String service = ctx.pathParam("name");
        if (!service.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new BadRequestResponse("\"" + service + "\" is not a service name");
        }
        return service;
    }
}
