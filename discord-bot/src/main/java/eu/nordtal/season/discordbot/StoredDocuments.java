package eu.nordtal.season.discordbot;

import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.DocumentMerge;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Documents;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.dv8tion.jda.api.utils.FileUpload;

/** Holds the bot's packaged documents and the ones admins wrote over them. */
public final class StoredDocuments {

    /** The namespace of every document the bot shows. */
    public static final String NAMESPACE = "discord";

    private final DocumentStore store;
    private final Map<String, Documents.Packaged> packaged =
            Documents.packaged(StoredDocuments.class.getClassLoader(), NAMESPACE);
    private volatile Map<String, Document> written = Map.of();

    public StoredDocuments(final DocumentStore store) {
        this.store = store;
    }

    void follow(final SignalHub hub) {
        store.follow(List.of(NAMESPACE), hub, this::hold);
    }

    /** Takes the admins' documents, each merged into this jar's declaration. */
    void hold(final List<DocumentStore.StoredDocument> read) {
        final Map<String, Document> byName = new HashMap<>();
        for (final DocumentStore.StoredDocument stored : read) {
            final Documents.Packaged own = packaged.get(stored.name());
            if (NAMESPACE.equals(stored.namespace()) && own != null) {
                byName.put(
                        stored.name(),
                        DocumentMerge.merge(own, stored.document(), stored.replaced())
                                .document());
            }
        }
        written = Map.copyOf(byName);
    }

    /**
     * Returns the packaged declaration and the document shown for {@code spec}: an admin's, else the packaged one.
     *
     * @throws IllegalStateException if the jar packages no such document
     */
    public Documents.Packaged applied(final DocumentSpec<?> spec) {
        final Documents.Packaged own = packaged.get(spec.name());
        if (own == null) {
            throw new IllegalStateException("the jar packages no document " + spec.id());
        }
        final Document document = written.get(spec.name());
        return document == null ? own : new Documents.Packaged(own.declaration(), document);
    }

    /**
     * Returns the attachment that an {@code upload://<id>} address shows.
     * It is empty for another address or an image that is not stored.
     * The message names the file as {@code attachment://} followed by its name.
     */
    public Optional<FileUpload> upload(final String address) {
        return DocumentStore.Image.idOf(address)
                .flatMap(store::image)
                .map(image -> FileUpload.fromData(image.bytes(), name(image)));
    }

    static String name(final DocumentStore.Image image) {
        return "upload-" + image.id() + "."
                + image.mediaType().substring("image/".length()).replace("jpeg", "jpg");
    }
}
