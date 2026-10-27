package eu.nordtal.season.steward.messages;

import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.database.inbox.DocumentPreview;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.discordrendering.DiscordRenderer;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.MessageOverride;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.DocumentCheck;
import eu.nordtal.season.messages.document.DocumentDeclaration;
import eu.nordtal.season.messages.document.DocumentMerge;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.document.Part;
import eu.nordtal.season.messages.document.Text;
import eu.nordtal.season.messages.document.Value;
import eu.nordtal.season.messages.text.Filling;
import eu.nordtal.season.messages.text.MessageCheck;
import eu.nordtal.season.messages.text.MessageSyntaxException;
import eu.nordtal.season.messages.text.MessageText;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jspecify.annotations.Nullable;

/**
 * The routes for the documents of Discord and of the game's menus, read from the jars by steward-agent.
 * An admin's document is a row. The bot's renderer draws every Discord preview, the game's every menu.
 * {@link DocumentCheck} refuses a save the build would refuse.
 */
public final class DocumentsApi {

    /** The largest image stored. */
    public static final int IMAGE_MEBIBYTES = 8;

    public static final int IMAGE_BYTES = IMAGE_MEBIBYTES * 1024 * 1024;

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    /** A shared part's name, as a document's name is written. */
    private static final Pattern SHARED_PART_NAME = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");

    private static final Document NONE = new Document(List.of());

    /** The prefix of a shared part's name in the journal. */
    private static final String SHARED_PARTS = "shared-parts/";

    private final AgentClient agent;
    private final DocumentStore store;
    private final SettingStore settings;
    private final MessageOverrideStore overrides;
    private volatile @Nullable Renderer renderer;

    /**
     * @param store     the admins' documents and images
     * @param settings  the network's languages and prices
     * @param overrides the admins' texts, of which the building blocks fill every drawing
     */
    public DocumentsApi(
            final AgentClient agent,
            final DocumentStore store,
            final SettingStore settings,
            final MessageOverrideStore overrides) {
        this.agent = agent;
        this.store = store;
        this.settings = settings;
        this.overrides = overrides;
    }

    /**
     * Every document once, with the one an admin wrote over it.
     *
     * @param languages the network's languages, the default first
     * @param sharedParts every shared part, by name
     */
    public record DocumentList(
            List<DocumentView> documents, List<String> languages, List<SharedPartView> sharedParts) {}

    /**
     * A part an admin saved for reuse.
     *
     * @param documents every admin's document that holds a copy of it, as {@code namespace/name}
     */
    public record SharedPartView(String name, Part part, List<String> documents) {}

    /**
     * An admin's document that a change of a shared part rewrites.
     *
     * @param problems what a save of {@code after} would be refused for
     */
    public record SharedPartChange(
            DocumentDeclaration declaration, boolean game, Document before, Document after, List<String> problems) {}

    /**
     * @param services the services whose jars ship it
     * @param game     whether the game draws it rather than Discord, as {@link DocumentSpec.Shape#game()} says
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
            boolean game,
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
     * A game text is drawn by the game's renderer instead.
     *
     * @param discord    the draft in Discord's JSON, or {@code null} for a game text
     * @param menu       the menu as the game draws it, or {@code null} for a draft that is not a menu
     * @param game       a chat text's, tab list's or kick screen's lines in MiniMessage, or {@code null} for any other
     * @param components how many components the draft draws at its largest
     * @param most       how many components Discord draws in one message
     * @param problems   what a save of the draft would be refused or warned for
     */
    public record DocumentDrawing(
            @Nullable JsonObject discord,
            @Nullable MenuDrawing menu,
            @Nullable List<String> game,
            int components,
            int most,
            List<DocumentCheck.Problem> problems) {}

    /** A menu's title and each item and label, in the order the document holds them. */
    public record MenuDrawing(String title, List<MenuPart> parts) {}

    /** @param lines an item's name and then its lore in MiniMessage, or a label's one plain line */
    public record MenuPart(String slot, Part.Type type, List<String> lines) {}

    public record Uploaded(long id, String address) {}

    /**
     * One save of a document, newest first.
     *
     * @param document what was saved, carried over to the jar's declaration, or {@code null} for the packaged one
     */
    public record DocumentVersion(long id, @Nullable Document document, Actor actor, Instant saved) {}

    /**
     * A document in one language that names a building block an admin changes, drawn before and after the change.
     *
     * @param document the admin's document, else the packaged one
     */
    public record BlockChange(
            DocumentDeclaration declaration,
            Document document,
            String language,
            DocumentDrawing before,
            DocumentDrawing after) {

        /** Returns each error of {@code after} that {@code before} does not have. */
        public List<String> breaks() {
            final Set<String> had = before.problems().stream()
                    .filter(DocumentCheck.Problem::error)
                    .map(DocumentCheck.Problem::text)
                    .collect(Collectors.toSet());
            return after.problems().stream()
                    .filter(problem -> problem.error() && !had.contains(problem.text()))
                    .map(DocumentCheck.Problem::text)
                    .toList();
        }
    }

    public void list(final Context ctx) {
        ctx.json(documents());
    }

    /**
     * {@code POST /api/documents/render} draws a {@link DocumentDraft} with the bot's renderer.
     * It refuses a draft the renderer cannot draw, such as a section with neither button nor thumbnail.
     */
    public void render(final Context ctx) {
        final DocumentDraft draft = draftOf(ctx);
        ctx.json(draw(draft.declaration(), draft.document(), draft.language(), renderer(languages())));
    }

    /** Draws a document in {@code language} with {@code renderer}, and refuses one the renderer cannot draw. */
    private static DocumentDrawing draw(
            final DocumentDeclaration declaration,
            final Document document,
            final String language,
            final Renderer renderer) {
        final @Nullable String drawn;
        final @Nullable MenuDrawing menu;
        final @Nullable List<String> game;
        final int components;
        final List<DocumentCheck.Problem> problems;
        try {
            components = DocumentCheck.components(declaration, document);
            problems = DocumentCheck.check(declaration, document, MessageCheck.Mode.OVERRIDE, renderer::blocks);
            final Documents.Packaged packaged = new Documents.Packaged(declaration, document);
            final Locale locale = Locales.parse(language);
            final DocumentSpec.Shape shape = declaration.shape();
            drawn = shape.game() ? null : renderer.documents().preview(packaged, renderer.languages(), locale);
            menu = shape == DocumentSpec.Shape.MENU ? menu(renderer.game(), packaged, locale) : null;
            game = shape.game() && menu == null ? text(renderer.game(), packaged, locale) : null;
        } catch (final RuntimeException undrawable) {
            throw new RequestRefused(
                    422, ANSWER.draftUndrawable(declaration.id(), String.valueOf(undrawable.getMessage())));
        }
        return new DocumentDrawing(
                drawn == null ? null : Json.tree(drawn).getAsJsonObject(),
                menu,
                game,
                components,
                DocumentCheck.COMPONENTS,
                problems);
    }

    /**
     * Returns each document that names a building block of {@code keys}, in each of its languages.
     * Each one is drawn with the stored building blocks and with {@code after}.
     *
     * @param after every admin's text of the block bundles once the change is written
     */
    List<BlockChange> blockChanges(final Set<String> keys, final Renderer after) {
        final Renderer before = renderer(after.languages());
        final List<BlockChange> changes = new ArrayList<>();
        for (final DocumentView view : documents().documents()) {
            final Document document = Objects.requireNonNullElse(view.written(), view.packaged());
            if (!names(document, keys)) {
                continue;
            }
            for (final String language : document.languages()) {
                changes.add(new BlockChange(
                        view.declaration(),
                        document,
                        language,
                        draw(view.declaration(), document, language, before),
                        draw(view.declaration(), document, language, after)));
            }
        }
        return changes;
    }

    /** Returns whether a text of {@code document} names one of the building blocks {@code keys} itself. */
    private static boolean names(final Document document, final Set<String> keys) {
        for (final Text text : document.texts()) {
            for (final List<String> versions : text.languages().values()) {
                for (final String version : versions) {
                    try {
                        if (MessageText.parse(version, false).blocks().stream().anyMatch(keys::contains)) {
                            return true;
                        }
                    } catch (final MessageSyntaxException unreadable) {
                        // The check reports it.
                    }
                }
            }
        }
        return false;
    }

    /**
     * {@code POST /api/document-preview} reads a {@link DocumentDraft} to show the admin alone.
     * The bot sends a Discord one as a direct message, the game server the admin is on shows a game one. It refuses a
     * draft that no message holds, one a save would refuse and one the renderer cannot draw.
     */
    public DocumentPreview preview(final Context ctx) {
        final DocumentDraft draft = draftOf(ctx);
        final DocumentDeclaration declaration = draft.declaration();
        final DocumentSpec.Shape shape = declaration.shape();
        if (!shape.game() && shape != DocumentSpec.Shape.MESSAGE && shape != DocumentSpec.Shape.PLAIN) {
            throw new RequestRefused(400, ANSWER.draftNotMessage(declaration.id()));
        }
        final List<String> problems;
        try {
            problems = DocumentCheck.errors(
                    declaration, draft.document(), MessageCheck.Mode.OVERRIDE, renderer(languages())::blocks);
        } catch (final RuntimeException unreadable) {
            throw new RequestRefused(
                    400, ANSWER.draftRefused(declaration.id(), String.valueOf(unreadable.getMessage())));
        }
        if (!problems.isEmpty()) {
            throw new RequestRefused(400, ANSWER.draftRefused(declaration.id(), problems.getFirst()));
        }
        final Documents.Packaged packaged = new Documents.Packaged(declaration, draft.document());
        final List<Locale> languages = languages();
        final Locale language = Locales.parse(draft.language());
        try {
            if (shape == DocumentSpec.Shape.MENU) {
                menu(renderer(languages).game(), packaged, language);
            } else if (shape.game()) {
                text(renderer(languages).game(), packaged, language);
            } else {
                renderer(languages).documents().preview(packaged, languages, language);
            }
        } catch (final RuntimeException undrawable) {
            throw new RequestRefused(
                    422, ANSWER.draftUndrawable(declaration.id(), String.valueOf(undrawable.getMessage())));
        }
        return new DocumentPreview(
                declaration.namespace(),
                declaration.name(),
                draft.language(),
                DocumentPreview.shownOf(shape),
                draft.document());
    }

    private static DocumentDraft draftOf(final Context ctx) {
        final DocumentDraft draft;
        try {
            draft = Json.decode(ctx.body(), DocumentDraft.class);
        } catch (final RuntimeException malformed) {
            throw new RequestRefused(400, ANSWER.notJson());
        }
        if (draft == null || draft.declaration() == null || draft.document() == null || draft.language() == null) {
            throw new RequestRefused(400, ANSWER.empty());
        }
        return draft;
    }

    /** Draws a menu with the game's renderer, every value, entry and data list item at its declared example. */
    private static MenuDrawing menu(
            final MessageRenderer renderer, final Documents.Packaged packaged, final Locale language) {
        final GameDocument document = GameDocument.example(renderer, packaged, Viewer.of(language));
        final List<MenuPart> parts = new ArrayList<>();
        for (final Part part : packaged.document().parts()) {
            final String slot = part.slot();
            if (slot == null) {
                continue;
            }
            if (part.type() == Part.Type.ITEM) {
                final GameDocument.Drawn drawn = document.exampleItem(slot);
                final List<String> lines = new ArrayList<>();
                lines.add(markup(drawn.name()));
                drawn.lore().forEach(line -> lines.add(markup(line)));
                parts.add(new MenuPart(slot, part.type(), lines));
            } else if (part.type() == Part.Type.LABEL) {
                parts.add(new MenuPart(slot, part.type(), List.of(document.exampleLabel(slot))));
            }
        }
        return new MenuDrawing(markup(document.title()), parts);
    }

    /** Draws a game text's lines with the game's renderer, every value at its declared example. */
    private static List<String> text(
            final MessageRenderer renderer, final Documents.Packaged packaged, final Locale language) {
        return GameDocument.example(renderer, packaged, Viewer.of(language)).lines().stream()
                .map(DocumentsApi::markup)
                .toList();
    }

    /**
     * Returns the component in MiniMessage, with each glyph tag as written.
     * Steward has no glyph table, so the renderer leaves the tag as text, and the browser draws the glyph by its name.
     */
    private static String markup(final Component component) {
        return MiniMessage.miniMessage().serialize(named(component)).replace("\\<glyph:", "<glyph:");
    }

    /** Puts each translated name's English fallback in its place: a browser has no client to translate it. */
    private static Component named(final Component component) {
        final Component named = component instanceof final TranslatableComponent translated
                ? Component.text(Objects.requireNonNullElse(translated.fallback(), translated.key()))
                        .style(translated.style())
                        .children(translated.children())
                : component;
        return named.children(named.children().stream().map(DocumentsApi::named).toList());
    }

    /**
     * {@code PUT /api/documents/{namespace}/{name}} stores the document in the body over the packaged one.
     * A document the check refuses is a 400 that writes nothing. The bot edits its managed messages at once.
     *
     * @return the document's {@code namespace/name}
     */
    public String save(final Context ctx, final Actor actor) {
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
            problems = DocumentCheck.errors(
                    declaration, document, MessageCheck.Mode.OVERRIDE, renderer(languages())::blocks);
        } catch (final RuntimeException unreadable) {
            throw new RequestRefused(
                    400, ANSWER.documentRefused(declaration.id(), String.valueOf(unreadable.getMessage())));
        }
        if (!problems.isEmpty()) {
            throw new RequestRefused(400, ANSWER.documentRefused(declaration.id(), problems.getFirst()));
        }
        try {
            store.change(
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
     * {@code DELETE /api/documents/{namespace}/{name}} removes the admin's document, so the packaged one is live again.
     *
     * @return the document's {@code namespace/name}
     */
    public String reset(final Context ctx, final Actor actor) {
        final DocumentDeclaration declaration = packagedOf(ctx).declaration();
        store.remove(declaration.namespace(), declaration.name(), actor);
        ctx.json(documents());
        return declaration.id();
    }

    /**
     * {@code GET /api/documents/{namespace}/{name}/versions} answers every save of the document, newest first.
     * A version is restored by saving its document, or by {@link #reset} where it has none.
     */
    public void versions(final Context ctx) {
        final Documents.Packaged packaged = packagedOf(ctx);
        final DocumentDeclaration declaration = packaged.declaration();
        ctx.json(store.versions(declaration.namespace(), declaration.name()).stream()
                .map(version -> {
                    final DocumentStore.StoredDocument saved = version.document();
                    return new DocumentVersion(
                            version.id(),
                            saved == null
                                    ? null
                                    : DocumentMerge.merge(packaged, saved.document(), saved.replaced())
                                            .document(),
                            version.actor(),
                            version.saved());
                })
                .toList());
    }

    /**
     * {@code POST /api/shared-parts/{name}/changes} answers each document that holds the shared part.
     * Each one is answered before and after the part in the body replaces every copy. It changes nothing.
     */
    public void sharedPartChanges(final Context ctx) {
        ctx.json(changes(sharedPartName(ctx), partOf(ctx)).stream()
                .map(Rewrite::change)
                .toList());
    }

    /**
     * {@code PUT /api/shared-parts/{name}} stores the part in the body as the shared part.
     * It replaces every copy in the same transaction, and writes nothing if the check refuses one changed document.
     *
     * @return the shared part's {@code shared-parts/name} and every document it rewrote
     */
    public List<String> saveSharedPart(final Context ctx, final Actor actor) {
        final String name = sharedPartName(ctx);
        final Part part = partOf(ctx).marked(null);
        final List<Rewrite> changes = changes(name, part);
        for (final Rewrite each : changes) {
            if (!each.change().problems().isEmpty()) {
                throw new RequestRefused(
                        400,
                        ANSWER.documentRefused(
                                each.change().declaration().id(),
                                each.change().problems().getFirst()));
            }
        }
        return writeSharedPart(ctx, name, part, changes, actor);
    }

    /**
     * {@code DELETE /api/shared-parts/{name}} removes the shared part; each copy stays as a plain part.
     *
     * @return the shared part's {@code shared-parts/name} and every document it rewrote
     */
    public List<String> removeSharedPart(final Context ctx, final Actor actor) {
        final String name = sharedPartName(ctx);
        return writeSharedPart(ctx, name, null, changes(name, null), actor);
    }

    private List<String> writeSharedPart(
            final Context ctx,
            final String name,
            final @Nullable Part part,
            final List<Rewrite> changes,
            final Actor actor) {
        try {
            store.changeSharedPart(
                    name,
                    part,
                    changes.stream()
                            .map(each -> new DocumentStore.StoredDocument(
                                    each.change().declaration().namespace(),
                                    each.change().declaration().name(),
                                    each.change().after(),
                                    each.hash()))
                            .toList(),
                    actor);
        } catch (final IllegalArgumentException missing) {
            throw new RequestRefused(
                    400, ANSWER.documentRefused(SHARED_PARTS + name, String.valueOf(missing.getMessage())));
        }
        ctx.json(documents());
        final List<String> written = new ArrayList<>();
        written.add(SHARED_PARTS + name);
        changes.forEach(each -> written.add(each.change().declaration().id()));
        return written;
    }

    /** A document a shared part's change rewrites, with the hash of the packaged one it is written over. */
    private record Rewrite(SharedPartChange change, String hash) {}

    private List<Rewrite> changes(final String name, final @Nullable Part part) {
        final List<Rewrite> changes = new ArrayList<>();
        final Renderer drawing = renderer(languages());
        for (final DocumentView view : documents().documents()) {
            final Document before = view.written();
            if (before == null || !before.sharedParts().contains(name)) {
                continue;
            }
            final Document after = before.withSharedPart(name, part);
            List<String> problems;
            try {
                problems = DocumentCheck.errors(view.declaration(), after, MessageCheck.Mode.OVERRIDE, drawing::blocks);
            } catch (final RuntimeException unreadable) {
                problems = List.of(String.valueOf(unreadable.getMessage()));
            }
            changes.add(new Rewrite(
                    new SharedPartChange(view.declaration(), view.game(), before, after, problems), view.hash()));
        }
        return changes;
    }

    private static String sharedPartName(final Context ctx) {
        final String name = ctx.pathParam("name");
        if (name.length() > 64 || !SHARED_PART_NAME.matcher(name).matches()) {
            throw new RequestRefused(400, ANSWER.sharedPartName(name));
        }
        return name;
    }

    private static Part partOf(final Context ctx) {
        final @Nullable Part part;
        try {
            part = Json.decode(ctx.body(), Part.class);
        } catch (final RuntimeException malformed) {
            throw new RequestRefused(400, ANSWER.notJson());
        }
        if (part == null) {
            throw new RequestRefused(400, ANSWER.empty());
        }
        return part;
    }

    /**
     * {@code POST /api/document-images} stores the PNG, JPEG, GIF or WebP image in the body.
     * It reads the type from the first bytes, not from the request header.
     */
    public void upload(final Context ctx, final Actor actor) {
        final byte[] bytes = ctx.bodyAsBytes();
        final String type =
                mediaType(bytes).orElseThrow(() -> new RequestRefused(400, ANSWER.imageRefused(IMAGE_MEBIBYTES)));
        final long id = store.upload(type, bytes, actor);
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
        final long id = ctx.pathParamAsClass("id", Long.class).get();
        final DocumentStore.Image image = store.image(id).orElseThrow(NotFoundResponse::new);
        ctx.header("Cache-Control", "private, max-age=31536000, immutable");
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.contentType(image.mediaType());
        ctx.result(image.bytes());
    }

    /** Returns the network's languages, the default first. */
    List<Locale> languages() {
        return MessagesApi.languages(settings).stream().map(Locales::parse).toList();
    }

    /**
     * Returns the renderers of drafts in {@code languages} with the stored building blocks.
     * It returns the last ones again while the languages and the blocks stay.
     */
    Renderer renderer(final List<Locale> languages) {
        final List<MessageOverride> blocks = overrides.overrides(Messages.BLOCKS);
        final Renderer last = renderer;
        if (last != null && last.languages().equals(languages) && last.blocks().equals(blocks)) {
            return last;
        }
        final Renderer made = renderer(languages, blocks);
        renderer = made;
        return made;
    }

    /** Returns renderers of drafts in {@code languages} with {@code blocks}, the admins' texts of the block bundles. */
    static Renderer renderer(final List<Locale> languages, final List<MessageOverride> blocks) {
        final Messages messages = Messages.load(
                DocumentsApi.class.getClassLoader(), DocumentRenderer.BUNDLES, languages.toArray(Locale[]::new));
        messages.override(blocks);
        return new Renderer(
                languages,
                List.copyOf(blocks),
                messages,
                new DocumentRenderer(DiscordRenderer.of(messages)),
                MessageRenderer.of(messages));
    }

    /**
     * @param blocks the admins' texts of the block bundles that {@code messages} holds
     * @param game   the game's renderer, which draws a menu
     */
    record Renderer(
            List<Locale> languages,
            List<MessageOverride> blocks,
            Messages messages,
            DocumentRenderer documents,
            MessageRenderer game) {

        /** Returns the building blocks of a language tag. */
        Filling.Blocks blocks(final String language) {
            return messages.blocks(Locales.parse(language));
        }
    }

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

    /** Returns every document as {@code list} answers them. */
    DocumentList documents() {
        final Map<String, Shipped> shipped = shipped();
        final Map<String, DocumentStore.StoredDocument> written = new LinkedHashMap<>();
        store.documents(shipped.values().stream()
                        .map(each -> each.packaged().declaration().namespace())
                        .distinct()
                        .toList())
                .forEach(document -> written.put(document.namespace() + "/" + document.name(), document));
        final List<DocumentView> views = new ArrayList<>();
        shipped.forEach((id, each) -> {
            final Documents.Packaged own = each.packaged();
            final DocumentStore.StoredDocument admin = written.get(id);
            final DocumentMerge.Merged merged = admin == null
                    ? new DocumentMerge.Merged(own.document(), List.of())
                    : DocumentMerge.merge(own, admin.document(), admin.replaced());
            views.add(new DocumentView(
                    own.declaration().withExampleValues(),
                    Value.withExampleValues(own.declaration().named()),
                    List.copyOf(each.services()),
                    own.declaration().shape().game(),
                    own.document(),
                    own.hash(),
                    admin == null ? null : merged.document(),
                    admin != null && !admin.replaced().equals(own.hash()),
                    merged.adaptations()));
        });
        final List<SharedPartView> sharedParts = store.sharedParts().stream()
                .map(shared -> new SharedPartView(
                        shared.name(),
                        shared.part(),
                        views.stream()
                                .filter(view -> Objects.requireNonNullElse(view.written(), NONE)
                                        .sharedParts()
                                        .contains(shared.name()))
                                .map(view -> view.declaration().id())
                                .toList()))
                .toList();
        return new DocumentList(views, MessagesApi.languages(settings), sharedParts);
    }
}
