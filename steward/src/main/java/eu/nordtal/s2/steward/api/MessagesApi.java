package eu.nordtal.s2.steward.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.database.message.MessageOverrideStore;
import eu.nordtal.s2.internalapi.agent.AgentClient;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.MessageBundle;
import eu.nordtal.s2.internalapi.agent.MessageEntry;
import eu.nordtal.s2.messages.MessageOverride;
import eu.nordtal.s2.messages.text.MessageCheck;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.NotFoundResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The routes over the message bundles: steward-agent reads the packaged texts out of the jars, the overrides are rows.
 *
 * Every process that loads a bundle re-reads its rows on the signal a save sends, so a save never asks anyone.
 */
public final class MessagesApi {

    /** The languages the editor writes. */
    private static final Set<String> LANGUAGES = Set.of("en", "de");

    private final AgentClient agent;
    private final @Nullable MessageOverrideStore overrides;

    /** @param overrides where the overrides are kept, or {@code null} without a database, which makes a save refused */
    public MessagesApi(final AgentClient agent, final @Nullable MessageOverrideStore overrides) {
        this.agent = agent;
        this.overrides = overrides;
    }

    /** {@code GET /api/messages}: every bundle found, without opening a single jar. */
    public void list(final Context ctx) {
        ctx.json(agent.bundles().stream().map(this::describe).toList());
    }

    /** {@code GET /api/messages/<bundle>}: one bundle, packaged text and override side by side. */
    public void one(final Context ctx) {
        final AgentWire.BundleRef location = locate(ctx);
        ctx.json(document(location, read(location)));
    }

    /**
     * {@code PUT /api/messages/<bundle>}: writes both languages' overrides as rows, at once for every process.
     * A {@code null} resets a key; what the one validator warns about is answered, what it refuses is a 400 that
     * writes nothing.
     */
    public void save(final Context ctx, final Actor actor) {
        final MessageOverrideStore store = overrides;
        final AgentWire.BundleRef location = locate(ctx);
        if (store == null) {
            throw new ForbiddenResponse("Steward has no database to keep an override in.");
        }
        final List<Change> changes = changesOf(bodyOf(ctx.body()));
        final Map<String, MessageEntry> entries = agent.bundle(location.service(), location.module()).entries().stream()
                .collect(Collectors.toMap(MessageEntry::key, entry -> entry, (first, second) -> first));
        final List<String> problems = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        for (final Change change : changes) {
            final MessageEntry entry = entries.get(change.key());
            if (entry == null) {
                throw new BadRequestResponse(identityOf(location) + " has no message " + change.key() + ".");
            }
            for (final MessageCheck.Problem problem : OverrideCheck.problems(entry, change.text())) {
                (problem.error() ? problems : warnings)
                        .add(entry.key() + " (" + change.language() + "): " + problem.text() + ".");
            }
        }
        if (!problems.isEmpty()) {
            throw new BadRequestResponse(String.join(" ", problems) + " Nothing was saved.");
        }
        for (final Change change : changes) {
            final MessageEntry entry = java.util.Objects.requireNonNull(entries.get(change.key()), change.key());
            store.change(
                    entry.bundle(),
                    change.key(),
                    change.language(),
                    change.text() == null ? List.of() : List.of(change.text()),
                    "de".equals(change.language()) ? entry.germanHash() : entry.englishHash(),
                    actor);
        }
        final Bundle bundle = document(location, read(location));
        ctx.json(new Saved(
                bundle.service(),
                bundle.module(),
                bundle.path(),
                bundle.writable(),
                bundle.entries(),
                warnings,
                Reloading.applied("Saved, and every service that shows it takes it at once.")));
    }

    /** The jar's bundles with the stored overrides beside the packaged texts, the first variant of each. */
    private MessageBundle read(final AgentWire.BundleRef location) {
        final MessageBundle packaged = agent.bundle(location.service(), location.module());
        final MessageOverrideStore store = overrides;
        if (store == null) {
            return packaged;
        }
        final Set<String> bundles =
                packaged.entries().stream().map(MessageEntry::bundle).collect(Collectors.toSet());
        final Map<String, String> first = new HashMap<>();
        for (final MessageOverride row : store.overrides(bundles)) {
            if (row.variant() == 0) {
                first.put(row.bundle() + "/" + row.key() + "/" + row.language(), row.text());
            }
        }
        return new MessageBundle(
                packaged.service(),
                packaged.module(),
                packaged.entries().stream()
                        .map(entry -> entry.withOverrides(
                                first.get(entry.bundle() + "/" + entry.key() + "/en"),
                                first.get(entry.bundle() + "/" + entry.key() + "/de")))
                        .toList());
    }

    // Finding the bundle

    private AgentWire.BundleRef locate(final Context ctx) {
        final String asked = ctx.pathParam("bundle");
        return agent.bundles().stream()
                .filter(location -> identityOf(location).equals(asked))
                .findFirst()
                .orElseThrow(() -> new NotFoundResponse("There is no message bundle called " + asked + "."));
    }

    /** How a bundle is named in a URL: {@code <service>/<module>}, or just {@code <service>}. */
    private static String identityOf(final AgentWire.BundleRef location) {
        return location.module().isEmpty() ? location.service() : location.service() + "/" + location.module();
    }

    // What goes over the wire

    /**
     * Where one bundle lives, as the listing names it.
     *
     * @param path {@code <service>/<module>}, or {@code <service>} with an empty module for the service's own jar
     * @param writable whether Steward has a database to keep an override in
     */
    public record BundleLocation(String service, String module, String path, boolean writable) {}

    /** One bundle, packaged text and override side by side for every key. */
    public record Bundle(String service, String module, String path, boolean writable, List<MessageEntry> entries) {}

    /**
     * What a save answers: the bundle as it now reads, every dropped placeholder warning, and that it applies.
     *
     * @param warnings the placeholders a text no longer carries, none of them blocking the save
     */
    public record Saved(
            String service,
            String module,
            String path,
            boolean writable,
            List<MessageEntry> entries,
            List<String> warnings,
            Reloading reload) {}

    private BundleLocation describe(final AgentWire.BundleRef location) {
        return new BundleLocation(location.service(), location.module(), identityOf(location), overrides != null);
    }

    private Bundle document(final AgentWire.BundleRef location, final MessageBundle bundle) {
        return new Bundle(
                location.service(), location.module(), identityOf(location), overrides != null, bundle.entries());
    }

    // What comes in

    /** One language's text for one key; a {@code null} text resets it to the packaged one. */
    private record Change(
            String key, String language, @Nullable String text) {}

    private static JsonObject bodyOf(final String body) {
        try {
            return Json.tree(body == null ? "" : body).getAsJsonObject();
        } catch (final JsonSyntaxException | IllegalStateException | IllegalArgumentException e) {
            throw new BadRequestResponse("The body has to be a JSON object with a `changes` field.");
        }
    }

    /** The changes in the order given. */
    private static List<Change> changesOf(final JsonObject body) {
        final JsonElement changes = body.get("changes");
        if (changes == null || !changes.isJsonObject()) {
            throw new BadRequestResponse("`changes` has to be an object of key to {\"en\": text,"
                    + " \"de\": text}, where null resets that language.");
        }
        final List<Change> all = new ArrayList<>();
        for (final Map.Entry<String, JsonElement> change :
                changes.getAsJsonObject().entrySet()) {
            if (!change.getValue().isJsonObject()) {
                throw new BadRequestResponse(
                        change.getKey() + " has to be an object of language to text, like {\"en\": \"...\"}.");
            }
            for (final Map.Entry<String, JsonElement> text :
                    change.getValue().getAsJsonObject().entrySet()) {
                if (!LANGUAGES.contains(text.getKey())) {
                    throw new BadRequestResponse("A language has to be \"en\" or \"de\", not " + text.getKey() + ".");
                }
                final JsonElement value = text.getValue();
                all.add(new Change(
                        change.getKey(),
                        text.getKey(),
                        value == null || value.isJsonNull() ? null : value.getAsString()));
            }
        }
        if (all.isEmpty()) {
            throw new BadRequestResponse("`changes` is empty - there is nothing to save.");
        }
        return all;
    }
}
