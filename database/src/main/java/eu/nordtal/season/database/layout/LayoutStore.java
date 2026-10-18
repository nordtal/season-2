package eu.nordtal.season.database.layout;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.messages.spec.layout.Element;
import eu.nordtal.season.messages.spec.layout.Part;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * Stores the Discord layouts that an editor rebuilt and the images an admin uploaded, network-wide.
 *
 * Steward writes them. A change to a layout signals its bundle on {@link Channel#MESSAGES}.
 */
public interface LayoutStore {

    /** Returns a store that borrows {@code dataSource}. */
    static LayoutStore using(final DataSource dataSource) {
        return new JdbiLayoutStore(dataSource);
    }

    /** Passes the rebuilds of {@code bundles} to {@code apply} on connect and on every change. */
    default void follow(
            final Collection<String> bundles, final SignalHub hub, final Consumer<List<StoredLayout>> apply) {
        hub.watch(Channel.MESSAGES, "the rebuilt layouts", null, () -> layouts(bundles), apply);
    }

    List<StoredLayout> layouts(Collection<String> bundles);

    /**
     * Replaces the rebuild of one layout, and signals its bundle.
     *
     * @throws IllegalArgumentException if a part shows an {@code upload://} image that is not stored
     */
    void change(StoredLayout layout, Actor actor);

    /** Removes the rebuild of one layout, so the packaged layout applies again, and signals its bundle. */
    void remove(String bundle, String layout);

    /** Stores an image and returns its id, which equal bytes share. */
    long upload(String mediaType, byte[] bytes, Actor actor);

    Optional<LayoutImage> image(long id);

    /**
     * One rebuilt layout, without the elements the code fills in.
     *
     * @param parts the tree, top to bottom
     * @param links the link buttons the editor added
     * @param replaced the SHA-256 of the packaged layout it replaces
     */
    record StoredLayout(String bundle, String layout, List<Part> parts, List<Element> links, String replaced) {

        public StoredLayout {
            parts = List.copyOf(parts);
            links = List.copyOf(links);
        }
    }

    /** An uploaded image, shown in a layout as {@code upload://<id>}. */
    final class LayoutImage {

        public static final String SCHEME = "upload://";

        private final long id;
        private final String mediaType;
        private final byte[] bytes;

        public LayoutImage(final long id, final String mediaType, final byte[] bytes) {
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
