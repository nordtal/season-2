package eu.nordtal.season.steward.messages;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.layout.LayoutStore;
import eu.nordtal.season.database.payment.Tier;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.layout.Element;
import eu.nordtal.season.messages.spec.layout.Layout;
import eu.nordtal.season.messages.spec.layout.LayoutCheck;
import eu.nordtal.season.messages.spec.layout.LayoutMerge;
import eu.nordtal.season.messages.spec.layout.LayoutSchema;
import eu.nordtal.season.messages.spec.layout.Part;
import eu.nordtal.season.messages.text.MessageCheck;
import eu.nordtal.season.settings.DatabaseSettings;
import eu.nordtal.season.settings.SettingsException;
import eu.nordtal.season.settings.network.NetworkSettings;
import eu.nordtal.season.steward.settings.Reloading;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The routes over the Discord layouts: steward-agent reads the declarations out of the jars, the rebuilds are rows.
 * Every process that loads a bundle re-reads its rebuilds on the signal a save sends.
 */
public final class LayoutsApi {

    /** The largest image stored, well below the 20 MiB per file that Discord's reference gives as its upload limit. */
    public static final int IMAGE_MEBIBYTES = 8;

    public static final int IMAGE_BYTES = IMAGE_MEBIBYTES * 1024 * 1024;

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    /** A language tag as a stored added text names it. */
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");

    private final AgentClient agent;
    private final @Nullable LayoutStore store;
    private final @Nullable SettingStore settings;

    /**
     * @param store    where the rebuilds and images are kept, or {@code null} without a database, which refuses a save
     * @param settings where the network's languages and prices are read, or {@code null} for the defaults
     */
    public LayoutsApi(
            final AgentClient agent, final @Nullable LayoutStore store, final @Nullable SettingStore settings) {
        this.agent = agent;
        this.store = store;
        this.settings = settings;
    }

    /**
     * Every Discord layout once, its declaration beside its stored rebuild, with what a preview fills in.
     *
     * @param writable       whether Steward has a database to keep a rebuild in
     * @param buildingBlocks every key shown in two or more layouts, to those layouts as {@code bundle/name}
     * @param languages      the network's languages as its settings name them now, the default first
     * @param tiers          the price tiers as the bot offers them now, cheapest first
     */
    public record Layouts(
            boolean writable,
            List<LayoutView> layouts,
            Map<String, List<String>> buildingBlocks,
            List<String> languages,
            List<Tier> tiers) {}

    /**
     * One layout.
     *
     * @param entry   the declaration with its packaged structure, the values its texts may name and every key it shows
     * @param hash    the SHA-256 of the packaged layout, which a rebuild records as written over
     * @param rebuild   the stored rebuild with the elements the code fills in, carried over to the packaged layout the
     *                  jar ships now, or {@code null} for none
     * @param adapted   what carrying the rebuild over changed, empty when it was written over this packaged layout
     * @param fallbacks the added texts the validator refuses now, which show their values' replacement words
     */
    public record LayoutView(
            LayoutSchema.Entry entry,
            String hash,
            @Nullable Layout rebuild,
            List<LayoutMerge.Adaptation> adapted,
            List<AddedFallback> fallbacks) {}

    /**
     * An added text the validator refuses, as a release that removed a value it names makes it.
     *
     * @param problems what the validator refuses, messages of the {@code check} bundle
     */
    public record AddedFallback(String language, String text, List<MessageRef> problems) {}

    /**
     * What a save answers.
     *
     * @param warnings what the validator warns about in an added text, none of it blocking the save
     */
    public record Saved(Layouts layouts, List<Warning> warnings, Reloading reload) {}

    /**
     * What the validator warns about in one added text.
     *
     * @param text a message of the {@code check} bundle
     */
    public record Warning(String language, MessageRef text) {}

    /** What an upload answers: the stored image's id and the address a part shows it by. */
    public record Uploaded(long id, String address) {}

    /** {@code GET /api/layouts}. */
    public void list(final Context ctx) {
        ctx.json(layouts());
    }

    /**
     * {@code PUT /api/layouts/{bundle}/{layout}}: stores the rebuild in the body as {@code {parts, links}}.
     * A rebuild the check refuses, or an added text the validator refuses, is a 400 that writes nothing.
     *
     * @return the bundle the rebuild was written for
     */
    public String save(final Context ctx, final Actor actor) {
        final LayoutStore stored = writable();
        final LayoutSchema.Entry entry = entryOf(ctx);
        final Layout packaged = entry.layout();
        final JsonObject body = bodyOf(ctx.body());
        final List<Part> parts = listOf(body, "parts", Part[].class);
        final List<Element> links = listOf(body, "links", Element[].class);
        final Layout rebuild = packaged.rebuilt(parts, links);
        final List<String> problems = new ArrayList<>(LayoutCheck.rebuildProblems(packaged, rebuild));
        rebuild.addedTexts().stream()
                .flatMap(texts -> texts.keySet().stream())
                .filter(language -> !LANGUAGE.matcher(language).matches())
                .forEach(language -> problems.add("an added text names the language " + language));
        if (!problems.isEmpty()) {
            throw new RequestRefused(400, ANSWER.rebuildRefused(packaged.name(), problems.getFirst()));
        }
        final List<Warning> warnings = new ArrayList<>();
        for (final Map<String, String> texts : rebuild.addedTexts()) {
            texts.forEach((language, text) -> {
                for (final MessageCheck.Problem problem :
                        MessageCheck.check(text, entry.values(), MessageCheck.Mode.OVERRIDE)) {
                    if (problem.error()) {
                        throw new RequestRefused(400, ANSWER.addedTextRefused(packaged.name(), language));
                    }
                    warnings.add(new Warning(language, problem.text()));
                }
            });
        }
        try {
            stored.change(
                    new LayoutStore.StoredLayout(
                            packaged.bundle(), packaged.name(), parts, links, LayoutSchema.hash(packaged)),
                    actor);
        } catch (final IllegalArgumentException missing) {
            throw new RequestRefused(400, ANSWER.rebuildRefused(packaged.name(), String.valueOf(missing.getMessage())));
        }
        ctx.json(new Saved(
                layouts(),
                warnings,
                Reloading.applied(StewardTexts.TEXTS.steward().said().message())));
        return packaged.bundle();
    }

    /**
     * {@code DELETE /api/layouts/{bundle}/{layout}}: removes the rebuild, so the packaged layout applies again.
     *
     * @return the bundle the rebuild was removed from
     */
    public String remove(final Context ctx) {
        final LayoutStore stored = writable();
        final Layout packaged = entryOf(ctx).layout();
        stored.remove(packaged.bundle(), packaged.name());
        ctx.json(new Saved(
                layouts(),
                List.of(),
                Reloading.applied(StewardTexts.TEXTS.steward().said().message())));
        return packaged.bundle();
    }

    /**
     * {@code POST /api/layout-images}: stores the image in the body, a PNG, JPEG, GIF or WebP file.
     * Its type is read from its first bytes, never from the request's header.
     */
    public void upload(final Context ctx, final Actor actor) {
        final LayoutStore stored = writable();
        // Javalin refuses a body above IMAGE_BYTES, as Web configures it, before this reads it.
        final byte[] bytes = ctx.bodyAsBytes();
        final String type =
                mediaType(bytes).orElseThrow(() -> new RequestRefused(400, ANSWER.imageRefused(IMAGE_MEBIBYTES)));
        final long id = stored.upload(type, bytes, actor);
        ctx.json(new Uploaded(id, LayoutStore.LayoutImage.SCHEME + id));
    }

    /** {@code GET /api/layout-images/{id}}: a stored image, which never changes under its id. */
    public void image(final Context ctx) {
        final LayoutStore stored = store;
        final long id = ctx.pathParamAsClass("id", Long.class).get();
        final LayoutStore.LayoutImage image = (stored == null
                        ? Optional.<LayoutStore.LayoutImage>empty()
                        : stored.image(id))
                .orElseThrow(NotFoundResponse::new);
        ctx.header("Cache-Control", "private, max-age=31536000, immutable");
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.contentType(image.mediaType());
        ctx.result(image.bytes());
    }

    /** Returns the media type a stored image's first bytes show, or empty for any other file. */
    static Optional<String> mediaType(final byte[] bytes) {
        if (starts(bytes, 0, new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})) {
            return Optional.of("image/png");
        }
        if (starts(bytes, 0, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            return Optional.of("image/jpeg");
        }
        if (starts(bytes, 0, ascii("GIF87a")) || starts(bytes, 0, ascii("GIF89a"))) {
            return Optional.of("image/gif");
        }
        if (starts(bytes, 0, ascii("RIFF")) && starts(bytes, 8, ascii("WEBP"))) {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    private static byte[] ascii(final String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean starts(final byte[] bytes, final int at, final byte[] prefix) {
        return bytes.length >= at + prefix.length
                && Arrays.equals(bytes, at, at + prefix.length, prefix, 0, prefix.length);
    }

    private LayoutStore writable() {
        final LayoutStore stored = store;
        if (stored == null) {
            throw new RequestRefused(403, ANSWER.noDatabase(StewardTexts.Kept.OVERRIDES));
        }
        return stored;
    }

    private LayoutSchema.Entry entryOf(final Context ctx) {
        final String bundle = ctx.pathParam("bundle");
        final String layout = ctx.pathParam("layout");
        final LayoutSchema.Entry entry = packaged().get(bundle + "/" + layout);
        if (entry == null) {
            throw new RequestRefused(404, ANSWER.noLayout(bundle, layout));
        }
        return entry;
    }

    /** Every layout of every jar once, by {@code bundle/name}, read from the first jar that carries it. */
    private Map<String, LayoutSchema.Entry> packaged() {
        final Map<String, LayoutSchema.Entry> entries = new LinkedHashMap<>();
        for (final AgentWire.BundleRef location : agent.bundles()) {
            for (final JsonObject json :
                    agent.bundle(location.service(), location.module()).layouts()) {
                final LayoutSchema.Entry entry = Json.decode(json.toString(), LayoutSchema.Entry.class);
                entries.putIfAbsent(
                        entry.layout().bundle() + "/" + entry.layout().name(), entry);
            }
        }
        return entries;
    }

    private Layouts layouts() {
        final Map<String, LayoutSchema.Entry> packaged = packaged();
        final Map<String, LayoutStore.StoredLayout> rebuilds = new LinkedHashMap<>();
        final LayoutStore stored = store;
        if (stored != null) {
            stored.layouts(packaged.values().stream()
                            .map(entry -> entry.layout().bundle())
                            .distinct()
                            .toList())
                    .forEach(rebuild -> rebuilds.put(rebuild.bundle() + "/" + rebuild.layout(), rebuild));
        }
        final List<LayoutView> views = new ArrayList<>();
        packaged.forEach((id, entry) -> {
            final Layout layout = entry.layout();
            final String hash = LayoutSchema.hash(layout);
            final LayoutStore.StoredLayout rebuild = rebuilds.get(id);
            if (rebuild == null) {
                views.add(new LayoutView(entry, hash, null, List.of(), List.of()));
                return;
            }
            final LayoutMerge.Merged merged =
                    LayoutMerge.merge(layout, rebuild.parts(), rebuild.links(), rebuild.replaced());
            final List<AddedFallback> fallbacks = new ArrayList<>();
            for (final Map<String, String> texts : merged.layout().addedTexts()) {
                texts.forEach((language, text) -> {
                    final List<MessageRef> problems = errors(text, entry);
                    if (!problems.isEmpty()) {
                        fallbacks.add(new AddedFallback(language, text, problems));
                    }
                });
            }
            views.add(new LayoutView(entry, hash, merged.layout(), merged.adaptations(), fallbacks));
        });
        return new Layouts(
                store != null,
                views,
                LayoutSchema.buildingBlocks(packaged.values()),
                MessagesApi.languages(settings),
                tiers());
    }

    /** What the validator refuses in an added text of the layout's entry, empty when it takes it. */
    private static List<MessageRef> errors(final String text, final LayoutSchema.Entry entry) {
        return MessageCheck.check(text, entry.values(), MessageCheck.Mode.OVERRIDE).stream()
                .filter(MessageCheck.Problem::error)
                .map(MessageCheck.Problem::text)
                .toList();
    }

    /** The price tiers as the network's settings name them now, cheapest first; none when they cannot be read. */
    private List<Tier> tiers() {
        final SettingStore stored = settings;
        if (stored == null) {
            return List.of();
        }
        try {
            return NetworkSettings.tiers(DatabaseSettings.current(stored, SettingStore.NETWORK, NetworkSettings.PRICES))
                    .all();
        } catch (final SettingsException unreadable) {
            return List.of();
        }
    }

    private static JsonObject bodyOf(final @Nullable String body) {
        try {
            return Json.tree(body == null ? "" : body).getAsJsonObject();
        } catch (final RuntimeException e) {
            throw new BadRequestResponse("The body has to be a JSON object of `parts` and `links`.");
        }
    }

    /** A list of the body, none when it is absent; one that does not read as {@code type} is a 400. */
    private static <T> List<T> listOf(final JsonObject body, final String field, final Class<T[]> type) {
        final JsonElement value = body.get(field);
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        try {
            return List.of(Json.decode(value.toString(), type));
        } catch (final RuntimeException e) {
            throw new BadRequestResponse("`" + field + "` is not a list of layout " + field + ": " + e.getMessage());
        }
    }
}
