package eu.nordtal.season.steward.messages;

import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.discordrendering.DiscordRenderer;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.DocumentCheck;
import eu.nordtal.season.messages.document.DocumentDeclaration;
import eu.nordtal.season.messages.document.DocumentMerge;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.document.Value;
import eu.nordtal.season.messages.text.MessageCheck;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The routes for the Discord documents.
 * steward-agent reads the declarations and documents from the jars, and an admin's document is a row.
 * The bot's own renderer draws every preview, and {@link DocumentCheck} refuses a save the build would refuse.
 */
public final class DocumentsApi {

    /** The largest image stored. */
    public static final int IMAGE_MEBIBYTES = 8;

    public static final int IMAGE_BYTES = IMAGE_MEBIBYTES * 1024 * 1024;

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private final AgentClient agent;
    private final @Nullable DocumentStore store;
    private final @Nullable SettingStore settings;
    private volatile @Nullable Renderer renderer;

    /**
     * @param store    the admins' documents and images, or {@code null} without a database
     * @param settings the network's languages and prices, or {@code null} for the defaults
     */
    public DocumentsApi(
            final AgentClient agent, final @Nullable DocumentStore store, final @Nullable SettingStore settings) {
        this.agent = agent;
        this.store = store;
        this.settings = settings;
    }

    /**
     * Every Discord document once, with the one an admin wrote over it.
     *
     * @param writable  whether Steward has a database for an admin's documents
     * @param languages the network's languages, the default first
     */
    public record DocumentList(boolean writable, List<DocumentView> documents, List<String> languages) {}

    /**
     * @param services the services whose jars ship it
     * @param packaged the document the jar ships
     * @param hash     the SHA-256 of the packaged declaration and document
     * @param named    every value a text may name, as {@link DocumentDeclaration#named()} lists them
     * @param written  the admin's document, carried over to the jar's declaration, or {@code null} for none
     * @param outdated whether the admin's document was written over another packaged one than the jar's
     * @param adapted  what the carry-over changed
     */
    public record DocumentView(
            DocumentDeclaration declaration,
            Map<String, Value> named,
            List<String> services,
            Document packaged,
            String hash,
            @Nullable Document written,
            boolean outdated,
            List<DocumentMerge.Adaptation> adapted) {}

    /**
     * A document to draw, with the declaration whose examples fill it.
     *
     * @param language the language its texts are drawn in
     */
    public record DocumentDraft(DocumentDeclaration declaration, Document document, String language) {}

    /**
     * A draft as the bot sends it, in Discord's own JSON: a message's payload, a modal, a content or a name.
     *
     * @param components how many components the draft draws at its largest
     * @param most       how many components Discord draws in one message
     * @param problems   what a save of the draft would be refused or warned for
     */
    public record DocumentDrawing(JsonObject discord, int components, int most, List<DocumentCheck.Problem> problems) {}

    public record Uploaded(long id, String address) {}

    public void list(final Context ctx) {
        ctx.json(documents());
    }

    /**
     * {@code POST /api/documents/render} draws a {@link DocumentDraft} with the bot's renderer.
     * It refuses a draft the renderer cannot draw, such as a section with neither button nor thumbnail.
     */
    public void render(final Context ctx) {
        final DocumentDraft draft;
        try {
            draft = Json.decode(ctx.body(), DocumentDraft.class);
        } catch (final RuntimeException malformed) {
            throw new RequestRefused(400, ANSWER.notJson());
        }
        if (draft == null || draft.declaration() == null || draft.document() == null || draft.language() == null) {
            throw new RequestRefused(400, ANSWER.empty());
        }
        final List<Locale> languages =
                MessagesApi.languages(settings).stream().map(Locales::parse).toList();
        final String drawn;
        final int components;
        final List<DocumentCheck.Problem> problems;
        try {
            components = DocumentCheck.components(draft.declaration(), draft.document());
            problems = DocumentCheck.check(draft.declaration(), draft.document(), MessageCheck.Mode.OVERRIDE);
            drawn = renderer(languages)
                    .preview(
                            new Documents.Packaged(draft.declaration(), draft.document()),
                            languages,
                            Locales.parse(draft.language()));
        } catch (final RuntimeException undrawable) {
            throw new RequestRefused(
                    422, ANSWER.draftUndrawable(draft.declaration().id(), String.valueOf(undrawable.getMessage())));
        }
        ctx.json(new DocumentDrawing(
                Json.tree(drawn).getAsJsonObject(), components, DocumentCheck.COMPONENTS, problems));
    }

    /**
     * {@code PUT /api/documents/{namespace}/{name}} stores the document in the body over the packaged one.
     * A document the check refuses is a 400 that writes nothing. The bot edits its managed messages at once.
     *
     * @return the document's {@code namespace/name}
     */
    public String save(final Context ctx, final Actor actor) {
        final DocumentStore stored = writable();
        final Documents.Packaged packaged = packagedOf(ctx);
        final DocumentDeclaration declaration = packaged.declaration();
        final Document document;
        try {
            document = Document.decode(ctx.body());
        } catch (final RuntimeException malformed) {
            throw new RequestRefused(400, ANSWER.notJson());
        }
        if (document == null) {
            throw new RequestRefused(400, ANSWER.empty());
        }
        final List<String> problems;
        try {
            problems = DocumentCheck.errors(declaration, document, MessageCheck.Mode.OVERRIDE);
        } catch (final RuntimeException unreadable) {
            throw new RequestRefused(
                    400, ANSWER.documentRefused(declaration.id(), String.valueOf(unreadable.getMessage())));
        }
        if (!problems.isEmpty()) {
            throw new RequestRefused(400, ANSWER.documentRefused(declaration.id(), problems.getFirst()));
        }
        try {
            stored.change(
                    new DocumentStore.StoredDocument(
                            declaration.namespace(), declaration.name(), document, packaged.hash()),
                    actor);
        } catch (final IllegalArgumentException missing) {
            throw new RequestRefused(
                    400, ANSWER.documentRefused(declaration.id(), String.valueOf(missing.getMessage())));
        }
        ctx.json(documents());
        return declaration.id();
    }

    /**
     * {@code POST /api/document-images} stores the PNG, JPEG, GIF or WebP image in the body.
     * It reads the type from the first bytes, not from the request header.
     */
    public void upload(final Context ctx, final Actor actor) {
        final DocumentStore stored = writable();
        final byte[] bytes = ctx.bodyAsBytes();
        final String type =
                mediaType(bytes).orElseThrow(() -> new RequestRefused(400, ANSWER.imageRefused(IMAGE_MEBIBYTES)));
        final long id = stored.upload(type, bytes, actor);
        ctx.json(new Uploaded(id, DocumentStore.Image.SCHEME + id));
    }

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

    private DocumentStore writable() {
        final DocumentStore stored = store;
        if (stored == null) {
            throw new RequestRefused(403, ANSWER.noDatabase(StewardTexts.Kept.OVERRIDES));
        }
        return stored;
    }

    private Documents.Packaged packagedOf(final Context ctx) {
        final String namespace = ctx.pathParam("namespace");
        final String name = ctx.pathParam("name");
        final Shipped shipped = shipped().get(namespace + "/" + name);
        if (shipped == null) {
            throw new RequestRefused(404, ANSWER.noDocument(namespace, name));
        }
        return shipped.packaged();
    }

    /** {@code GET /api/document-images/{id}} answers a stored image. */
    public void image(final Context ctx) {
        final DocumentStore stored = store;
        final long id = ctx.pathParamAsClass("id", Long.class).get();
        final DocumentStore.Image image = (stored == null ? Optional.<DocumentStore.Image>empty() : stored.image(id))
                .orElseThrow(NotFoundResponse::new);
        ctx.header("Cache-Control", "private, max-age=31536000, immutable");
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.contentType(image.mediaType());
        ctx.result(image.bytes());
    }

    /** Returns a renderer of drafts in {@code languages}, the last one again while the languages stay. */
    private DocumentRenderer renderer(final List<Locale> languages) {
        final Renderer last = renderer;
        if (last != null && last.languages().equals(languages)) {
            return last.renderer();
        }
        final DocumentRenderer made = new DocumentRenderer(DiscordRenderer.of(Messages.load(
                DocumentsApi.class.getClassLoader(), DocumentRenderer.BUNDLES, languages.toArray(Locale[]::new))));
        renderer = new Renderer(languages, made);
        return made;
    }

    private record Renderer(List<Locale> languages, DocumentRenderer renderer) {}

    /** A packaged document with the services whose jars ship it; the first jar's document wins. */
    private record Shipped(Documents.Packaged packaged, List<String> services) {}

    /** Every document the jars ship, by {@code namespace/name}. */
    private Map<String, Shipped> shipped() {
        final Map<String, Shipped> shipped = new LinkedHashMap<>();
        for (final AgentWire.BundleRef location : agent.bundles()) {
            for (final JsonObject json :
                    agent.bundle(location.service(), location.module()).documents()) {
                final DocumentDeclaration declaration =
                        Json.decode(json.get("declaration").toString(), DocumentDeclaration.class);
                final List<String> services = shipped.computeIfAbsent(
                                declaration.id(),
                                id -> new Shipped(
                                        new Documents.Packaged(
                                                declaration,
                                                Document.decode(
                                                        json.get("document").toString())),
                                        new ArrayList<>()))
                        .services();
                if (!services.contains(location.service())) {
                    services.add(location.service());
                }
            }
        }
        return shipped;
    }

    private DocumentList documents() {
        final Map<String, Shipped> shipped = shipped();
        final Map<String, DocumentStore.StoredDocument> written = new LinkedHashMap<>();
        final DocumentStore stored = store;
        if (stored != null) {
            stored.documents(shipped.values().stream()
                            .map(each -> each.packaged().declaration().namespace())
                            .distinct()
                            .toList())
                    .forEach(document -> written.put(document.namespace() + "/" + document.name(), document));
        }
        final List<DocumentView> views = new ArrayList<>();
        shipped.forEach((id, each) -> {
            final Documents.Packaged own = each.packaged();
            final DocumentStore.StoredDocument admin = written.get(id);
            final DocumentMerge.Merged merged = admin == null
                    ? new DocumentMerge.Merged(own.document(), List.of())
                    : DocumentMerge.merge(own, admin.document(), admin.replaced());
            views.add(new DocumentView(
                    own.declaration(),
                    own.declaration().named(),
                    List.copyOf(each.services()),
                    own.document(),
                    own.hash(),
                    admin == null ? null : merged.document(),
                    admin != null && !admin.replaced().equals(own.hash()),
                    merged.adaptations()));
        });
        return new DocumentList(store != null, views, MessagesApi.languages(settings));
    }
}
