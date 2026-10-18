package eu.nordtal.season.steward.messages;

import com.google.gson.JsonObject;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.database.payment.Tier;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.DocumentDeclaration;
import eu.nordtal.season.messages.document.DocumentMerge;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.settings.DatabaseSettings;
import eu.nordtal.season.settings.SettingsException;
import eu.nordtal.season.settings.network.NetworkSettings;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The routes for the Discord documents.
 * steward-agent reads the declarations and documents from the jars, and an admin's document is a row.
 * Steward shows them read only, so no route writes one.
 */
public final class DocumentsApi {

    private final AgentClient agent;
    private final @Nullable DocumentStore store;
    private final @Nullable SettingStore settings;

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
     * @param languages the network's languages, the default first
     * @param tiers     the price tiers, cheapest first
     */
    public record DocumentList(List<DocumentView> documents, List<String> languages, List<Tier> tiers) {}

    /**
     * @param services the services whose jars ship it
     * @param packaged the document the jar ships
     * @param hash     the SHA-256 of the packaged declaration and document
     * @param written  the admin's document, carried over to the jar's declaration, or {@code null} for none
     * @param adapted  what the carry-over changed
     */
    public record DocumentView(
            DocumentDeclaration declaration,
            List<String> services,
            Document packaged,
            String hash,
            @Nullable Document written,
            List<DocumentMerge.Adaptation> adapted) {}

    public void list(final Context ctx) {
        ctx.json(documents());
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
                    List.copyOf(each.services()),
                    own.document(),
                    own.hash(),
                    admin == null ? null : merged.document(),
                    merged.adaptations()));
        });
        return new DocumentList(views, MessagesApi.languages(settings), tiers());
    }

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
}
