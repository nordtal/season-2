package eu.nordtal.season.steward.messages;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.inbox.MessagePreview;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.MessageArg;
import eu.nordtal.season.internalapi.agent.MessageEntry;
import eu.nordtal.season.messages.MessageOverride;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.text.MessageCheck;
import eu.nordtal.season.messages.value.Kind;
import eu.nordtal.season.settings.Colours;
import eu.nordtal.season.settings.DatabaseSettings;
import eu.nordtal.season.settings.SettingsException;
import eu.nordtal.season.settings.network.NetworkSettings;
import eu.nordtal.season.steward.settings.Reloading;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final @Nullable SettingStore settings;

    /**
     * @param overrides where the overrides are kept, or {@code null} without a database, which makes a save refused
     * @param settings  where the network's languages and each service's colours are read, or {@code null} for the
     *     defaults
     */
    public MessagesApi(
            final AgentClient agent,
            final @Nullable MessageOverrideStore overrides,
            final @Nullable SettingStore settings) {
        this.agent = agent;
        this.overrides = overrides;
        this.settings = settings;
    }

    /** {@code GET /api/messages}: every text of every bundle once, packaged text and override side by side. */
    public void list(final Context ctx) {
        ctx.json(texts(layered(union())));
    }

    /**
     * {@code PUT /api/messages}: writes each language's override, its variants in order, as rows at once.
     * A {@code null} resets it; a warning of the validator is answered, a refusal is a 400 that writes nothing.
     *
     * @return the bundles a row was written for, in the order the body names them
     */
    public List<String> save(final Context ctx, final Actor actor) {
        final MessageOverrideStore store = overrides;
        if (store == null) {
            throw new RequestRefused(403, ANSWER.noDatabase(StewardTexts.Kept.OVERRIDES));
        }
        final List<Change> changes = changesOf(bodyOf(ctx.body()));
        final List<Found> union = union();
        final Map<String, MessageEntry> entries = new HashMap<>();
        union.forEach(found -> entries.put(idOf(found.entry()), found.entry()));
        final List<Warning> warnings = new ArrayList<>();
        for (final Change change : changes) {
            final MessageEntry entry = entries.get(change.bundle() + "/" + change.key());
            if (entry == null) {
                throw new RequestRefused(400, ANSWER.noMessage(change.bundle(), change.key()));
            }
            for (final String text : change.texts()) {
                for (final MessageCheck.Problem problem : OverrideCheck.problems(entry, text)) {
                    if (problem.error()) {
                        // The editor shows every problem as the text is typed, so the refusal only names the text.
                        throw new RequestRefused(400, ANSWER.overrideRefused(entry.key(), change.language()));
                    }
                    warnings.add(new Warning(entry.bundle(), entry.key(), change.language(), problem.text()));
                }
            }
        }
        final Set<String> written = new LinkedHashSet<>();
        for (final Change change : changes) {
            final MessageEntry entry =
                    Objects.requireNonNull(entries.get(change.bundle() + "/" + change.key()), change.key());
            store.change(
                    entry.bundle(),
                    change.key(),
                    change.language(),
                    change.texts(),
                    entry.packaged(change.language()),
                    actor);
            written.add(entry.bundle());
        }
        ctx.json(new Saved(
                texts(layered(union)),
                warnings,
                Reloading.applied(StewardTexts.TEXTS.steward().said().message())));
        return List.copyOf(written);
    }

    /**
     * {@code GET /api/message-check?bundle=&key=&text=}: what the one validator says of a text for a key.
     * Errors and warnings, each a message of the {@code check} bundle; the editor asks as the admin types, so it has
     * no check of its own.
     */
    public void check(final Context ctx) {
        final String key = ctx.queryParamAsClass("key", String.class).get();
        final String text = ctx.queryParamAsClass("text", String.class).get();
        final MessageEntry entry =
                entryOf(locate(ctx.queryParamAsClass("bundle", String.class).get()), key);
        ctx.json(OverrideCheck.problems(entry, text));
    }

    /**
     * {@code POST /api/message-preview}: a text an admin is trying, as the place {@code shown} shows it.
     * It is filled with {@code values}, in the palette of {@code service} or else the network's. What the validator
     * refuses is refused here too, and so is a place no preview reaches.
     */
    public MessagePreview preview(final Context ctx) {
        final JsonObject body = bodyOf(ctx.body());
        final String key = textOf(body, "key");
        final String language = textOf(body, "language");
        final String text = textOf(body, "text");
        if (!LANGUAGE.matcher(language).matches()) {
            throw new BadRequestResponse("A language is a lowercase tag like \"en\", not " + language + ".");
        }
        final AgentWire.BundleRef location = locate(textOf(body, "bundle"));
        final MessageEntry entry = entryOf(location, key);
        if (OverrideCheck.problems(entry, text).stream().anyMatch(MessageCheck.Problem::error)) {
            throw new RequestRefused(400, ANSWER.previewRefused(key, language));
        }
        final String place = textOf(body, "shown");
        final Display shown = previewable(entry).stream()
                .filter(display -> display.name().equals(place))
                .findFirst()
                .orElseThrow(() -> new RequestRefused(400, ANSWER.noPreview(key)));
        final JsonElement values = body.get("values");
        final JsonElement service = body.get("service");
        return new MessagePreview(
                exampleOf(entry, values != null && values.isJsonObject() ? values.getAsJsonObject() : new JsonObject()),
                language,
                text,
                shown,
                coloursOf(service != null && service.isJsonPrimitive() ? service.getAsString() : SettingStore.NETWORK));
    }

    /**
     * Every place of a key a preview reaches, in its schema's order.
     * None that Steward shows itself or pushes, and none of a key no schema describes.
     */
    static List<Display> previewable(final MessageEntry entry) {
        return entry.shown().stream()
                .map(Display::valueOf)
                .filter(display -> display.surface() != Display.Surface.STEWARD)
                .toList();
    }

    /**
     * Returns the key's message with a typed value for each of its own placeholders, actions and globals left out.
     * Each is the editor's, else the schema's example, else its kind's; one that none of them reads as its kind is
     * left out, and the text shows its name.
     */
    private static MessageRef exampleOf(final MessageEntry entry, final JsonObject values) {
        final Map<String, Object> args = new LinkedHashMap<>();
        for (final MessageArg arg : entry.args()) {
            if (arg.action() || arg.global()) {
                continue;
            }
            final String token = arg.kind();
            final Kind kind = token == null ? Kind.TEXT : Kind.byToken(token).orElse(Kind.TEXT);
            final JsonElement given = values.get(arg.name());
            final List<String> candidates = new ArrayList<>();
            if (given != null && given.isJsonPrimitive()) {
                candidates.add(given.getAsString());
            }
            if (arg.example() != null) {
                candidates.add(arg.example());
            }
            candidates.add(kind.defaultExample());
            for (final String candidate : candidates) {
                try {
                    args.put(arg.name(), kind.example(candidate));
                    break;
                } catch (final IllegalArgumentException | DateTimeException unread) {
                    // The next candidate, down to the kind's own example.
                }
            }
        }
        return new MessageRef(entry.key(), args);
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
        final Map<String, MessageEntry> entries = new HashMap<>();
        union().forEach(text -> entries.put(idOf(text.entry()), text.entry()));
        final Set<String> bundles =
                entries.values().stream().map(MessageEntry::bundle).collect(Collectors.toSet());
        final Map<String, List<MessageOverride>> sets = new LinkedHashMap<>();
        for (final MessageOverride row : store.overrides(bundles)) {
            sets.computeIfAbsent(row.bundle() + "/" + row.key() + "/" + row.language(), ignored -> new ArrayList<>())
                    .add(row);
        }
        final Map<String, List<String>> originals = store.originals(bundles);
        sets.forEach((name, rows) -> {
            final MessageOverride first = rows.getFirst();
            final MessageEntry entry = entries.get(first.bundle() + "/" + first.key());
            if (entry == null) {
                return;
            }
            final Fallback fallback = fallbackOf(entry, first.language(), rows, originals.get(name));
            if (fallback != null) {
                found.add(fallback);
            }
        });
        ctx.json(found);
    }

    private static @Nullable Fallback fallbackOf(
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
                entry.bundle(),
                entry.key(),
                language,
                stale ? FallbackReason.STALE : FallbackReason.REFUSED,
                texts,
                original,
                packaged,
                problems);
    }

    /**
     * Every key of every jar's bundles once, by bundle and key, in the agent's order of the jars.
     * A key is read from the first jar that carries it, and lists every service whose jar shows it.
     */
    private List<Found> union() {
        final Map<String, Found> byId = new LinkedHashMap<>();
        for (final AgentWire.BundleRef location : agent.bundles()) {
            for (final MessageEntry entry :
                    agent.bundle(location.service(), location.module()).entries()) {
                final Found found =
                        byId.computeIfAbsent(idOf(entry), id -> new Found(entry, location, new ArrayList<>()));
                if (!found.services().contains(location.service())) {
                    found.services().add(location.service());
                }
            }
        }
        return List.copyOf(byId.values());
    }

    /** The same keys with the stored overrides beside the packaged texts, every language and variant. */
    private List<Found> layered(final List<Found> union) {
        final MessageOverrideStore store = overrides;
        if (store == null) {
            return union;
        }
        final Set<String> bundles =
                union.stream().map(found -> found.entry().bundle()).collect(Collectors.toSet());
        // Bundle and key to language to variant number to text.
        final Map<String, Map<String, TreeMap<Integer, String>>> stored = new HashMap<>();
        for (final MessageOverride row : store.overrides(bundles)) {
            stored.computeIfAbsent(row.bundle() + "/" + row.key(), ignored -> new HashMap<>())
                    .computeIfAbsent(row.language(), ignored -> new TreeMap<>())
                    .put(row.variant(), row.text());
        }
        return union.stream()
                .map(found -> {
                    final Map<String, List<String>> languages = new HashMap<>();
                    stored.getOrDefault(idOf(found.entry()), Map.of())
                            .forEach((language, variants) -> languages.put(language, List.copyOf(variants.values())));
                    return new Found(found.entry().withOverrides(languages), found.location(), found.services());
                })
                .toList();
    }

    /** How a text is named across the network: its bundle and key, since two bundles may declare the same key. */
    private static String idOf(final MessageEntry entry) {
        return entry.bundle() + "/" + entry.key();
    }

    /**
     * One key as {@link #union} finds it.
     *
     * @param location the first jar that carries it, which the editor names to check and preview it
     * @param services every service whose jar ships it, in the agent's order
     */
    private record Found(MessageEntry entry, AgentWire.BundleRef location, List<String> services) {}

    // Finding the bundle

    private AgentWire.BundleRef locate(final String asked) {
        return agent.bundles().stream()
                .filter(location -> identityOf(location).equals(asked))
                .findFirst()
                .orElseThrow(() -> new RequestRefused(404, ANSWER.noBundle(asked)));
    }

    /** The key as the bundle's jar declares it, refused when it declares none. */
    private MessageEntry entryOf(final AgentWire.BundleRef location, final String key) {
        return agent.bundle(location.service(), location.module()).entries().stream()
                .filter(candidate -> candidate.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new RequestRefused(400, ANSWER.noMessage(identityOf(location), key)));
    }

    /** How a bundle is named in a URL: {@code <service>/<module>}, or just {@code <service>}. */
    private static String identityOf(final AgentWire.BundleRef location) {
        return location.module().isEmpty() ? location.service() : location.service() + "/" + location.module();
    }

    // What goes over the wire

    /**
     * Every text of every bundle once, packaged text and override side by side.
     *
     * @param writable  whether Steward has a database to keep an override in
     * @param languages the network's languages as its settings name them now, the default first
     * @param colours   for each service that shows a text, each tone's colour by its tag as its {@code colours}
     *                  settings name it now
     * @param places    every place a text can be shown, in order, with where it is
     */
    public record Texts(
            boolean writable,
            List<Text> texts,
            List<String> languages,
            Map<String, Map<String, String>> colours,
            Map<String, Display.Surface> places) {}

    /**
     * One key of one bundle, where it is read from and who shows it.
     *
     * @param path     the jar it is read from as the editor's check and preview name it, {@code <service>/<module>}
     * @param services every service whose jar ships it and that draws one of its places, as {@link TextServices} says
     * @param previews every place of it a preview reaches, to where it reaches the admin who asks for one, in its
     *                 schema's order; empty for a key none reaches
     */
    public record Text(MessageEntry entry, String path, List<String> services, Map<String, PreviewTarget> previews) {}

    /** Where an admin's preview of a key reaches them. */
    public enum PreviewTarget {
        /** Their linked player, on the game server the roster has them on. */
        GAME,
        /** A direct message from the bot. */
        DISCORD;

        /** Returns where a preview shown as {@code shown} reaches its admin. */
        public static PreviewTarget of(final Display shown) {
            return shown.surface() == Display.Surface.DISCORD ? DISCORD : GAME;
        }
    }

    /**
     * What a save answers: every text as it now reads, every dropped placeholder warning, and that it applies.
     *
     * @param warnings what the validator warns about, none of it blocking the save
     */
    public record Saved(Texts texts, List<Warning> warnings, Reloading reload) {}

    /**
     * What the validator warns about in one saved text, such as a value it no longer shows.
     *
     * @param text a message of the {@code check} bundle
     */
    public record Warning(String bundle, String key, String language, MessageRef text) {}

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
     * @param override the override's variants, in order
     * @param original the packaged texts it was written over, {@code null} where none were kept
     * @param packaged the packaged texts the jar ships now, which every process shows instead
     * @param problems what the validator refuses, messages of the {@code check} bundle, empty for a stale override
     */
    public record Fallback(
            String bundle,
            String key,
            String language,
            FallbackReason reason,
            List<String> override,
            @Nullable List<String> original,
            List<String> packaged,
            List<MessageRef> problems) {}

    private Texts texts(final List<Found> union) {
        final Map<String, Map<String, String>> colours = new TreeMap<>();
        final List<Text> texts = new ArrayList<>(union.size());
        for (final Found found : union) {
            final Map<String, PreviewTarget> places = new LinkedHashMap<>();
            previewable(found.entry()).forEach(place -> places.put(place.name(), PreviewTarget.of(place)));
            final List<String> services = TextServices.showing(
                    found.services(), found.entry().bundle(), found.entry().shown());
            texts.add(new Text(found.entry(), identityOf(found.location()), services, places));
            services.forEach(service -> colours.computeIfAbsent(service, this::coloursOf));
        }
        final Map<String, Display.Surface> places = new LinkedHashMap<>();
        for (final Display place : Display.values()) {
            places.put(place.name(), place.surface());
        }
        return new Texts(overrides != null, texts, languages(), colours, places);
    }

    /** The network's languages as its settings name them now, the default first; the defaults without a database. */
    private List<String> languages() {
        final SettingStore store = settings;
        if (store != null) {
            try {
                return NetworkSettings.languages(DatabaseSettings.current(
                                store, SettingStore.NETWORK, NetworkSettings.LANGUAGE_AND_TIME))
                        .tags();
            } catch (final SettingsException unreadable) {
                // Even the defaults refused: the editor still offers the defaults.
            }
        }
        return NetworkSettings.defaultLanguages().tags();
    }

    /** Each tone's colour by its tag, in the palette's order, as the {@code colours} of {@code service} say. */
    private Map<String, String> coloursOf(final String service) {
        final Map<String, String> colours = new LinkedHashMap<>();
        for (final Tone tone : Tone.values()) {
            colours.put(tone.tag(), tone.hex());
        }
        final SettingStore store = settings;
        if (store != null) {
            try {
                Colours.declared(DatabaseSettings.current(store, service, Colours.GROUP))
                        .forEach((tone, hex) -> colours.put(tone.tag(), hex));
            } catch (final SettingsException unreadable) {
                // Even the defaults refused: every tone keeps its own.
            }
        }
        return colours;
    }

    // What comes in

    /** One language's texts for one key of one bundle, its variants in order; none resets it to the packaged ones. */
    private record Change(String bundle, String key, String language, List<String> texts) {}

    private static JsonObject bodyOf(final String body) {
        try {
            return Json.tree(body == null ? "" : body).getAsJsonObject();
        } catch (final JsonSyntaxException | IllegalStateException | IllegalArgumentException e) {
            throw new BadRequestResponse("The body has to be a JSON object.");
        }
    }

    /** A field of the body that has to be a text. */
    private static String textOf(final JsonObject body, final String field) {
        final JsonElement value = body.get(field);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new BadRequestResponse("`" + field + "` has to be a text.");
        }
        return value.getAsString();
    }

    /** The changes in the order given: bundle to key to language to its variants. */
    private static List<Change> changesOf(final JsonObject body) {
        final JsonElement changes = body.get("changes");
        if (changes == null || !changes.isJsonObject()) {
            throw new BadRequestResponse(
                    "`changes` has to be an object of bundle to key to {\"<language>\": [variants]},"
                            + " where null resets that language.");
        }
        final List<Change> all = new ArrayList<>();
        for (final Map.Entry<String, JsonElement> bundle :
                changes.getAsJsonObject().entrySet()) {
            if (!bundle.getValue().isJsonObject()) {
                throw new BadRequestResponse(bundle.getKey() + " has to be an object of key to its languages.");
            }
            for (final Map.Entry<String, JsonElement> change :
                    bundle.getValue().getAsJsonObject().entrySet()) {
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
                    all.add(new Change(
                            bundle.getKey(),
                            change.getKey(),
                            text.getKey(),
                            variantsOf(change.getKey(), text.getValue())));
                }
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
