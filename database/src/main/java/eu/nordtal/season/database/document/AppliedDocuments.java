package eu.nordtal.season.database.document;

import eu.nordtal.season.database.inbox.DocumentPreview;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.DocumentMerge;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Documents;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Holds a process's packaged documents and the ones admins wrote over them, each merged into this release. */
public final class AppliedDocuments implements Documents.Source {

    private final Set<String> namespaces;
    /** By namespace, then name. */
    private final Map<String, Map<String, Documents.Packaged>> packaged = new HashMap<>();

    private volatile Map<String, Document> written = Map.of();

    /** Reads the documents of {@code namespaces} that the jar ships. */
    public AppliedDocuments(final ClassLoader loader, final Set<String> namespaces) {
        this.namespaces = Set.copyOf(namespaces);
        this.namespaces.forEach(namespace -> packaged.put(namespace, Documents.packaged(loader, namespace)));
    }

    /** Takes the admins' documents on connect and on every change, then runs {@code changed}. */
    public void follow(final DocumentStore store, final SignalHub hub, final Runnable changed) {
        store.follow(namespaces, hub, read -> {
            hold(read);
            changed.run();
        });
    }

    /** Takes the admins' documents, each merged into this jar's declaration. */
    public void hold(final List<DocumentStore.StoredDocument> read) {
        final Map<String, Document> byId = new HashMap<>();
        for (final DocumentStore.StoredDocument stored : read) {
            final Documents.Packaged own =
                    packaged.getOrDefault(stored.namespace(), Map.of()).get(stored.name());
            if (own != null) {
                byId.put(
                        own.declaration().id(),
                        DocumentMerge.merge(own, stored.document(), stored.replaced())
                                .document());
            }
        }
        written = Map.copyOf(byId);
    }

    @Override
    public Documents.Packaged applied(final DocumentSpec<?> spec) {
        final Documents.Packaged own = own(spec.namespace(), spec.name());
        final Document document = written.get(spec.id());
        return document == null ? own : new Documents.Packaged(own.declaration(), document);
    }

    /**
     * Returns an admin's draft with the declaration this jar packages for its document.
     *
     * @throws IllegalStateException if the jar packages no such document
     */
    public Documents.Packaged drafted(final DocumentPreview preview) {
        return new Documents.Packaged(own(preview.namespace(), preview.name()).declaration(), preview.document());
    }

    private Documents.Packaged own(final String namespace, final String name) {
        final Documents.Packaged own =
                packaged.getOrDefault(namespace, Map.of()).get(name);
        if (own == null) {
            throw new IllegalStateException("the jar packages no document " + namespace + "/" + name);
        }
        return own;
    }
}
