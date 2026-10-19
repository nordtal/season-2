package eu.nordtal.season.database.document;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.Part;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Stores the documents an admin wrote in place of packaged ones, and the images an admin uploaded, network-wide.
 * Every save is kept as a {@link Version}. A change signals its namespace on {@link Channel#MESSAGES}.
 */
public interface DocumentStore {

    /** Returns a store that borrows {@code dataSource}. */
    static DocumentStore using(final DataSource dataSource) {
        return new JdbiDocumentStore(dataSource);
    }

    /** Passes the documents of {@code namespaces} to {@code apply} on connect and on every change. */
    default void follow(
            final Collection<String> namespaces, final SignalHub hub, final Consumer<List<StoredDocument>> apply) {
        hub.watch(Channel.MESSAGES, "the admins' documents", null, () -> documents(namespaces), apply);
    }

    List<StoredDocument> documents(Collection<String> namespaces);

    /**
     * Replaces an admin's document, keeps it as a version, and signals its namespace.
     *
     * @throws IllegalArgumentException if it shows an {@code upload://} image that is not stored
     */
    void change(StoredDocument document, Actor actor);

    /** Removes an admin's document, so the packaged one applies again, keeps that as a version, and signals. */
    void remove(String namespace, String name, Actor actor);

    /** Returns every shared part, by name. */
    List<SharedPart> sharedParts();

    /**
     * Replaces a shared part, or removes it where {@code part} is {@code null}, with the documents that hold it.
     * Each document is written and kept as a version in the same transaction, and each namespace written is signalled.
     *
     * @throws IllegalArgumentException if a document shows an {@code upload://} image that is not stored
     */
    void changeSharedPart(String name, @Nullable Part part, List<StoredDocument> rewritten, Actor actor);

    /** Returns every version of a document, the newest first. */
    List<Version> versions(String namespace, String name);

    /** Stores an image and returns its id, which equal bytes share. */
    long upload(String mediaType, byte[] bytes, Actor actor);

    Optional<Image> image(long id);

    /** @param replaced the hash of the packaged document it was written over */
    record StoredDocument(String namespace, String name, Document document, String replaced) {

        public StoredDocument {
            Objects.requireNonNull(document, "document");
        }
    }

    /** A part an admin saved for reuse; a document holds a copy marked with its name. */
    record SharedPart(String name, Part part) {}

    /**
     * One save of a document.
     *
     * @param document the document saved, or {@code null} where the save returned to the packaged one
     */
    record Version(long id, @Nullable StoredDocument document, Actor actor, Instant saved) {}

    /** An uploaded image, shown in a document as {@code upload://<id>}. */
    final class Image {

        public static final String SCHEME = "upload://";

        private final long id;
        private final String mediaType;
        private final byte[] bytes;

        public Image(final long id, final String mediaType, final byte[] bytes) {
            this.id = id;
            this.mediaType = mediaType;
            this.bytes = bytes.clone();
        }

        public long id() {
            return id;
        }

        public String mediaType() {
            return mediaType;
        }

        public byte[] bytes() {
            return bytes.clone();
        }

        /** Returns the id of an {@code upload://<id>} address, or empty for another address. */
        public static Optional<Long> idOf(final String address) {
            return address.startsWith(SCHEME)
                    ? Optional.of(Long.parseLong(address.substring(SCHEME.length())))
                    : Optional.empty();
        }
    }
}
