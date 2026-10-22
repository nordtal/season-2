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

/** The routes for the message bundles. steward-agent reads the packaged texts, and overrides are database rows. */
public final class MessagesApi {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    /** A lowercase language tag. */
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}(-[a-z0-9]+)*");

    private final AgentClient agent;
    private final @Nullable MessageOverrideStore overrides;
    private final @Nullable SettingStore settings;

    /**
     * @param overrides the override store, or {@code null} without a database, which makes {@code save} refuse
     * @param settings  the store of the network's languages and each service's colours, or {@code null} for defaults
     */
    public MessagesApi(
            final AgentClient agent,
            final @Nullable MessageOverrideStore overrides,
            final @Nullable SettingStore settings) {
        this.agent = agent;
        this.overrides = overrides;
        this.settings = settings;
    }

    /** {@code GET /api/messages} answers every text of every bundle with its packaged text and its override. */
    public void list(final Context ctx) {
        ctx.json(texts(layered(union())));
    }

    /**
     * {@code PUT /api/messages} writes the override of each named language as rows, and a {@code null} resets one.
     * A validator error is a 400 that writes nothing.
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
                        // The refusal names the text only.
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

    /** {@code GET /api/message-check?bundle=&key=&text=} answers the validator's errors and warnings for a text. */
    public void check(final Context ctx) {
        final String key = ctx.queryParamAsClass("key", String.class).get();
        final String text = ctx.queryParamAsClass("text", String.class).get();
        final MessageEntry entry =
                entryOf(locate(ctx.queryParamAsClass("bundle", String.class).get()), key);
        ctx.json(OverrideCheck.problems(entry, text));
    }

    /**
     * {@code POST /api/message-preview} renders a text for the place {@code shown} with {@code values}.
     * It uses the palette of {@code service}, else the network's. It refuses a text the validator refuses and a place
     * no preview reaches.
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
                key,
                language,
                text,
                shown,
                service != null && service.isJsonPrimitive() ? service.getAsString() : SettingStore.NETWORK,
                givenOf(entry, values != null && values.isJsonObject() ? values.getAsJsonObject() : new JsonObject()));
    }

    /** Returns the value given for each placeholder of the key's own, as typed. The recipient reads it. */
    private static Map<String, String> givenOf(final MessageEntry entry, final JsonObject values) {
        final Map<String, String> given = new LinkedHashMap<>();
        for (final MessageArg arg : entry.args()) {
            final JsonElement value = values.get(arg.name());
            if (!arg.action() && !arg.global() && value != null && value.isJsonPrimitive()) {
                given.put(arg.name(), value.getAsString());
            }
        }
        return given;
    }

    /** Returns the places of a key that a preview reaches, in schema order, without the Steward surface. */
    static List<Display> previewable(final MessageEntry entry) {
        return entry.shown().stream()
                .map(Display::valueOf)
                .filter(display -> display.surface() != Display.Surface.STEWARD)
                .toList();
    }

    /**
     * {@code GET /api/message-syntax} answers the tones with their default colours.
     * It also answers the kinds with their sorted styles.
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
     * {@code GET /api/message-fallbacks} answers every override that is stale or has a validator error.
     * Processes show the packaged text for these.
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

    /** Returns each key once, read from the first jar that has it, with every service whose jar has it. */
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

    private List<Found> layered(final List<Found> union) {
        final MessageOverrideStore store = overrides;
        if (store == null) {
            return union;
        }
        final Set<String> bundles =
                union.stream().map(found -> found.entry().bundle()).collect(Collectors.toSet());
        // Maps bundle/key to language to variant number to text.
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

    private static String idOf(final MessageEntry entry) {
        return entry.bundle() + "/" + entry.key();
    }

    /**
     * One key as {@link #union} finds it.
     *
     * @param location the first jar that has the key
     * @param services every service whose jar has the key
     */
    private record Found(MessageEntry entry, AgentWire.BundleRef location, List<String> services) {}

    private AgentWire.BundleRef locate(final String asked) {
        return agent.bundles().stream()
                .filter(location -> identityOf(location).equals(asked))
                .findFirst()
                .orElseThrow(() -> new RequestRefused(404, ANSWER.noBundle(asked)));
    }

    /** Returns the key from the bundle's jar and refuses an undeclared key. */
    private MessageEntry entryOf(final AgentWire.BundleRef location, final String key) {
        return agent.bundle(location.service(), location.module()).entries().stream()
                .filter(candidate -> candidate.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new RequestRefused(400, ANSWER.noMessage(identityOf(location), key)));
    }

    /** Returns {@code <service>/<module>}, or {@code <service>} without a module. */
    private static String identityOf(final AgentWire.BundleRef location) {
        return location.module().isEmpty() ? location.service() : location.service() + "/" + location.module();
    }

    /**
     * The answer to {@code list}.
     *
     * @param writable  whether Steward has a database for overrides
     * @param languages the network's languages, the default first
     * @param colours   each tone's colour by tag, for each service that shows a text
     * @param places    every place a text can be shown with its surface
     */
    public record Texts(
            boolean writable,
            List<Text> texts,
            List<String> languages,
            Map<String, Map<String, String>> colours,
            Map<String, Display.Surface> places) {}

    /**
     * One key of one bundle.
     *
     * @param path     the jar as {@code <service>/<module>}
     * @param services every service whose jar has the key and that draws one of its places, as {@link TextServices}
     *                 says
     * @param previews each place a preview reaches with its target, in schema order
     */
    public record Text(MessageEntry entry, String path, List<String> services, Map<String, PreviewTarget> previews) {}

    public enum PreviewTarget {
        /** The linked player of the admin, on the game server the roster has them on. */
        GAME,
        /** A direct message from the bot. */
        DISCORD;

        /** Returns the target of a preview shown as {@code shown}. */
        public static PreviewTarget of(final Display shown) {
            return shown.surface() == Display.Surface.DISCORD ? DISCORD : GAME;
        }
    }

    /**
     * The answer to {@code save}.
     *
     * @param warnings the validator warnings, which do not block the save
     */
    public record Saved(Texts texts, List<Warning> warnings, Reloading reload) {}

    /**
     * One validator warning for a saved text.
     *
     * @param text a message of the {@code check} bundle
     */
    public record Warning(String bundle, String key, String language, MessageRef text) {}

    /**
     * The answer to {@code syntax}.
     *
     * @param tones every tone's tag with its colour
     * @param kinds every value kind's token with its sorted styles
     */
    public record Syntax(Map<String, String> tones, Map<String, List<String>> kinds) {}

    /** The reason a process shows the packaged text instead of an override. */
    public enum FallbackReason {
        /** A release changed the packaged texts that the override was written over. */
        STALE,
        /** The validator refuses the override. */
        REFUSED
    }

    /**
     * An override that processes do not show.
     *
     * @param override the override's variants, in order
     * @param original the packaged texts the override was written over, or {@code null}
     * @param packaged the packaged texts of the jar
     * @param problems the validator errors as messages of the {@code check} bundle, empty for a stale override
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
        return new Texts(overrides != null, texts, languages(settings), colours, places);
    }

    /** Returns the network's languages, the default first, and the defaults without a database. */
    static List<String> languages(final @Nullable SettingStore store) {
        if (store != null) {
            try {
                return NetworkSettings.languages(DatabaseSettings.current(
                                store, SettingStore.NETWORK, NetworkSettings.LANGUAGE_AND_TIME))
                        .tags();
            } catch (final SettingsException unreadable) {
                // Falls back to the default languages.
            }
        }
        return NetworkSettings.defaultLanguages().tags();
    }

    /** Returns each tone's colour by tag, with the {@code colours} settings of {@code service} applied. */
    private Map<String, String> coloursOf(final String service) {
        final Map<String, String> colours = new LinkedHashMap<>();
        for (final Tone tone : Tone.values()) {
            colours.put(tone.tag(), tone.hex());
        }
        final SettingStore store = settings;
        if (store != null) {
            Colours.current(store, service).forEach((tone, hex) -> colours.put(tone.tag(), hex));
        }
        return colours;
    }

    /** The variants of one language for one key of one bundle. No variants reset it to the packaged texts. */
    private record Change(String bundle, String key, String language, List<String> texts) {}

    private static JsonObject bodyOf(final String body) {
        try {
            return Json.tree(body == null ? "" : body).getAsJsonObject();
        } catch (final JsonSyntaxException | IllegalStateException | IllegalArgumentException e) {
            throw new BadRequestResponse("The body has to be a JSON object.");
        }
    }

    /** Returns the body field {@code field}, which must be a text. */
    private static String textOf(final JsonObject body, final String field) {
        final JsonElement value = body.get(field);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new BadRequestResponse("`" + field + "` has to be a text.");
        }
        return value.getAsString();
    }

    /** Returns the changes in body order, read from bundle to key to language to variants. */
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

    /** Returns the variants of a language, and none for a {@code null}. */
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
