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
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Tone;
import eu.nordtal.s2.messages.text.MessageCheck;
import eu.nordtal.s2.messages.value.Kind;
import eu.nordtal.s2.steward.texts.RequestRefused;
import eu.nordtal.s2.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The routes over the message bundles: steward-agent reads the packaged texts out of the jars, the overrides are rows.
 *
 * Every process that loads a bundle re-reads its rows on the signal a save sends, so a save never asks anyone.
 */
public final class MessagesApi {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    /** A lowercase language tag, which names a bundle file and a row's language alike. */
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}(-[a-z0-9]+)*");

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
     * {@code PUT /api/messages/<bundle>}: writes each language's override, its variants in order, as rows at once.
     * A {@code null} resets a key's language; what the one validator warns about is answered, what it refuses is a
     * 400 that writes nothing.
     */
    public void save(final Context ctx, final Actor actor) {
        final MessageOverrideStore store = overrides;
        final AgentWire.BundleRef location = locate(ctx);
        if (store == null) {
            throw new RequestRefused(403, ANSWER.noDatabase(StewardTexts.Kept.OVERRIDES));
        }
        final List<Change> changes = changesOf(bodyOf(ctx.body()));
        final Map<String, MessageEntry> entries = agent.bundle(location.service(), location.module()).entries().stream()
                .collect(Collectors.toMap(MessageEntry::key, entry -> entry, (first, second) -> first));
        final List<Warning> warnings = new ArrayList<>();
        for (final Change change : changes) {
            final MessageEntry entry = entries.get(change.key());
            if (entry == null) {
                throw new RequestRefused(400, ANSWER.noMessage(identityOf(location), change.key()));
            }
            for (final String text : change.texts()) {
                for (final MessageCheck.Problem problem : OverrideCheck.problems(entry, text)) {
                    if (problem.error()) {
                        // The editor shows every problem as the text is typed, so the refusal only names the text.
                        throw new RequestRefused(400, ANSWER.overrideRefused(entry.key(), change.language()));
                    }
                    warnings.add(new Warning(entry.key(), change.language(), problem.text()));
                }
            }
        }
        for (final Change change : changes) {
            final MessageEntry entry = java.util.Objects.requireNonNull(entries.get(change.key()), change.key());
            store.change(
                    entry.bundle(),
                    change.key(),
                    change.language(),
                    change.texts(),
                    entry.packaged(change.language()),
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
                Reloading.applied(StewardTexts.TEXTS.steward().said().message())));
    }

    /**
     * {@code GET /api/message-check?bundle=&key=&text=}: what the one validator says of a text for a key.
     * Errors and warnings, each a message of the {@code check} bundle; the editor asks as the admin types, so it has
     * no check of its own.
     */
    public void check(final Context ctx) {
        final String key = ctx.queryParamAsClass("key", String.class).get();
        final String text = ctx.queryParamAsClass("text", String.class).get();
        final AgentWire.BundleRef location =
                locate(ctx.queryParamAsClass("bundle", String.class).get());
        final MessageEntry entry = agent.bundle(location.service(), location.module()).entries().stream()
                .filter(candidate -> candidate.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new RequestRefused(400, ANSWER.noMessage(identityOf(location), key)));
        ctx.json(OverrideCheck.problems(entry, text));
    }

    /**
     * {@code GET /api/message-syntax}: the tones and value styles a text may name, for the editor's menus.
     * A tone comes with the colour it has where a service's {@code colours} settings name none, in the palette's
     * order; a kind with the styles it offers besides its own way, sorted.
     */
    public void syntax(final Context ctx) {
        final Map<String, String> tones = new LinkedHashMap<>();
        for (final Tone tone : Tone.values()) {
            tones.put(tone.tag(), tone.hex());
        }
        final Map<String, List<String>> kinds = new LinkedHashMap<>();
        for (final Kind kind : Kind.values()) {
            kinds.put(kind.token(), kind.styles().stream().sorted().toList());
        }
        ctx.json(new Syntax(tones, kinds));
    }

    /**
     * {@code GET /api/message-fallbacks}: every override no process shows, so the packaged text shows instead.
     * Either a release changed the packaged texts it was written over, or the one validator refuses it; saving it
     * again takes it over.
     */
    public void fallbacks(final Context ctx) {
        final MessageOverrideStore store = overrides;
        final List<Fallback> found = new ArrayList<>();
        if (store == null) {
            ctx.json(found);
            return;
        }
        final Set<String> seen = new HashSet<>();
        for (final AgentWire.BundleRef location : agent.bundles()) {
            final Map<String, MessageEntry> entries = new HashMap<>();
            agent.bundle(location.service(), location.module())
                    .entries()
                    .forEach(entry -> entries.putIfAbsent(entry.bundle() + "/" + entry.key(), entry));
            final Set<String> bundles =
                    entries.values().stream().map(MessageEntry::bundle).collect(Collectors.toSet());
            final Map<String, List<MessageOverride>> sets = new LinkedHashMap<>();
            for (final MessageOverride row : store.overrides(bundles)) {
                sets.computeIfAbsent(
                                row.bundle() + "/" + row.key() + "/" + row.language(), ignored -> new ArrayList<>())
                        .add(row);
            }
            final Map<String, List<String>> originals = store.originals(bundles);
            sets.forEach((name, rows) -> {
                final MessageOverride first = rows.getFirst();
                final MessageEntry entry = entries.get(first.bundle() + "/" + first.key());
                if (entry == null || !seen.add(name)) {
                    return;
                }
                final Fallback fallback =
                        fallbackOf(identityOf(location), entry, first.language(), rows, originals.get(name));
                if (fallback != null) {
                    found.add(fallback);
                }
            });
        }
        ctx.json(found);
    }

    private static @Nullable Fallback fallbackOf(
            final String path,
            final MessageEntry entry,
            final String language,
            final List<MessageOverride> rows,
            final @Nullable List<String> original) {
        final List<String> texts = rows.stream().map(MessageOverride::text).toList();
        final List<String> packaged = entry.packaged(language);
        final boolean stale = rows.getFirst().staleOver(packaged);
        final List<MessageRef> problems = new ArrayList<>();
        for (final String text : texts) {
            for (final MessageCheck.Problem problem : OverrideCheck.problems(entry, text)) {
                if (problem.error()) {
                    problems.add(problem.text());
                }
            }
        }
        if (!stale && problems.isEmpty()) {
            return null;
        }
        return new Fallback(
                path,
                entry.bundle(),
                entry.key(),
                language,
                stale ? FallbackReason.STALE : FallbackReason.REFUSED,
                texts,
                original,
                packaged,
                problems);
    }

    /** The jar's bundles with the stored overrides beside the packaged texts, every language and variant. */
    private MessageBundle read(final AgentWire.BundleRef location) {
        final MessageBundle packaged = agent.bundle(location.service(), location.module());
        final MessageOverrideStore store = overrides;
        if (store == null) {
            return packaged;
        }
        final Set<String> bundles =
                packaged.entries().stream().map(MessageEntry::bundle).collect(Collectors.toSet());
        // Bundle and key to language to variant number to text.
        final Map<String, Map<String, TreeMap<Integer, String>>> stored = new HashMap<>();
        for (final MessageOverride row : store.overrides(bundles)) {
            stored.computeIfAbsent(row.bundle() + "/" + row.key(), ignored -> new HashMap<>())
                    .computeIfAbsent(row.language(), ignored -> new TreeMap<>())
                    .put(row.variant(), row.text());
        }
        return new MessageBundle(
                packaged.service(),
                packaged.module(),
                packaged.entries().stream()
                        .map(entry -> {
                            final Map<String, List<String>> languages = new HashMap<>();
                            stored.getOrDefault(entry.bundle() + "/" + entry.key(), Map.of())
                                    .forEach((language, variants) ->
                                            languages.put(language, List.copyOf(variants.values())));
                            return entry.withOverrides(languages);
                        })
                        .toList());
    }

    // Finding the bundle

    private AgentWire.BundleRef locate(final Context ctx) {
        return locate(ctx.pathParam("bundle"));
    }

    private AgentWire.BundleRef locate(final String asked) {
        return agent.bundles().stream()
                .filter(location -> identityOf(location).equals(asked))
                .findFirst()
                .orElseThrow(() -> new RequestRefused(404, ANSWER.noBundle(asked)));
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
     * @param warnings what the validator warns about, none of it blocking the save
     */
    public record Saved(
            String service,
            String module,
            String path,
            boolean writable,
            List<MessageEntry> entries,
            List<Warning> warnings,
            Reloading reload) {}

    /**
     * What the validator warns about in one saved text, such as a value it no longer shows.
     *
     * @param text a message of the {@code check} bundle
     */
    public record Warning(String key, String language, MessageRef text) {}

    /**
     * What a message text may name besides its values.
     *
     * @param tones every tone's tag with its colour, in the palette's order
     * @param kinds every value kind's token with the styles it offers, sorted; empty for a kind with none
     */
    public record Syntax(Map<String, String> tones, Map<String, List<String>> kinds) {}

    /** Why a process shows the packaged text instead of an override. */
    public enum FallbackReason {
        /** A release changed the packaged texts the override was written over. */
        STALE,
        /** The one validator refuses the override, which the release's declaration no longer allows. */
        REFUSED
    }

    /**
     * An override set aside, beside what it was written over and what the jar ships now.
     *
     * @param path     the bundle as the listing names it, {@code <service>/<module>}
     * @param override the override's variants, in order
     * @param original the packaged texts it was written over, {@code null} where none were kept
     * @param packaged the packaged texts the jar ships now, which every process shows instead
     * @param problems what the validator refuses, messages of the {@code check} bundle, empty for a stale override
     */
    public record Fallback(
            String path,
            String bundle,
            String key,
            String language,
            FallbackReason reason,
            List<String> override,
            @Nullable List<String> original,
            List<String> packaged,
            List<MessageRef> problems) {}

    private BundleLocation describe(final AgentWire.BundleRef location) {
        return new BundleLocation(location.service(), location.module(), identityOf(location), overrides != null);
    }

    private Bundle document(final AgentWire.BundleRef location, final MessageBundle bundle) {
        return new Bundle(
                location.service(), location.module(), identityOf(location), overrides != null, bundle.entries());
    }

    // What comes in

    /** One language's texts for one key, its variants in order; none resets it to the packaged ones. */
    private record Change(String key, String language, List<String> texts) {}

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
            throw new BadRequestResponse("`changes` has to be an object of key to {\"<language>\": [variants]},"
                    + " where null resets that language.");
        }
        final List<Change> all = new ArrayList<>();
        for (final Map.Entry<String, JsonElement> change :
                changes.getAsJsonObject().entrySet()) {
            if (!change.getValue().isJsonObject()) {
                throw new BadRequestResponse(change.getKey()
                        + " has to be an object of language to its variants, like {\"en\": [\"...\"]}.");
            }
            for (final Map.Entry<String, JsonElement> text :
                    change.getValue().getAsJsonObject().entrySet()) {
                if (!LANGUAGE.matcher(text.getKey()).matches()) {
                    throw new BadRequestResponse(
                            "A language is a lowercase tag like \"en\", not " + text.getKey() + ".");
                }
                all.add(new Change(change.getKey(), text.getKey(), variantsOf(change.getKey(), text.getValue())));
            }
        }
        if (all.isEmpty()) {
            throw new BadRequestResponse("`changes` is empty - there is nothing to save.");
        }
        return all;
    }

    /** A language's variants in order, none for a {@code null} that resets it. */
    private static List<String> variantsOf(final String key, final @Nullable JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        if (!value.isJsonArray() || value.getAsJsonArray().isEmpty()) {
            throw new BadRequestResponse(key + " has to name each language's variants as a list of texts, or null.");
        }
        final List<String> variants = new ArrayList<>();
        for (final JsonElement variant : value.getAsJsonArray()) {
            if (!variant.isJsonPrimitive() || !variant.getAsJsonPrimitive().isString()) {
                throw new BadRequestResponse(key + " has a variant that is not a text.");
            }
            variants.add(variant.getAsString());
        }
        return variants;
    }
}
