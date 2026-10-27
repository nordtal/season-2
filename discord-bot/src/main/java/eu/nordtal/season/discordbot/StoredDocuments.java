package eu.nordtal.season.discordbot;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.database.inbox.DocumentPreview;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Documents;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.dv8tion.jda.api.utils.FileUpload;

/** Holds the bot's packaged documents and the ones admins wrote over them. */
public final class StoredDocuments implements DocumentRenderer.Source {

    /** The namespace of every document the bot shows. */
    public static final String NAMESPACE = "discord";

    private final DocumentStore store;
    private final AppliedDocuments documents =
            new AppliedDocuments(StoredDocuments.class.getClassLoader(), Set.of(NAMESPACE));

    public StoredDocuments(final DocumentStore store) {
        this.store = store;
    }

    /** Takes the admins' documents on connect and on every change, then runs {@code changed}. */
    void follow(final SignalHub hub, final Runnable changed) {
        documents.follow(store, hub, changed);
    }

    void hold(final List<DocumentStore.StoredDocument> read) {
        documents.hold(read);
    }

    /**
     * @throws IllegalStateException if the jar packages no such document
     */
    @Override
    public Documents.Packaged applied(final DocumentSpec<?> spec) {
        return documents.applied(spec);
    }

    /**
     * @throws IllegalStateException if the jar packages no such document
     */
    public Documents.Packaged drafted(final DocumentPreview preview) {
        return documents.drafted(preview);
    }

    /**
     * Returns the attachment that an {@code upload://<id>} address shows.
     * It is empty for another address or an image that is not stored.
     * The message names the file as {@code attachment://} followed by its name.
     */
    @Override
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
