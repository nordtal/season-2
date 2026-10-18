package eu.nordtal.season.discordbot;

import eu.nordtal.season.database.layout.LayoutStore;
import eu.nordtal.season.database.notify.SignalHub;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.dv8tion.jda.api.utils.FileUpload;

/**
 * The rebuilt layouts of the bot's bundles as Steward stored them, re-read on every change, and their uploaded images.
 * A layout is drawn from its rebuild where one is stored, else from its declaration.
 */
public final class StoredLayouts {

    private final LayoutStore store;
    private volatile Map<String, LayoutStore.StoredLayout> rebuilds = Map.of();

    StoredLayouts(final LayoutStore store) {
        this.store = store;
    }

    /** Reads the rebuilds of {@code bundles} as {@code hub} connects and on every change, before the hub starts. */
    void follow(final Collection<String> bundles, final SignalHub hub) {
        store.follow(bundles, hub, read -> {
            final Map<String, LayoutStore.StoredLayout> byId = new LinkedHashMap<>();
            read.forEach(rebuild -> byId.put(rebuild.bundle() + "/" + rebuild.layout(), rebuild));
            rebuilds = Map.copyOf(byId);
        });
    }

    /** Returns the stored rebuild of a layout, or empty where its declaration applies. */
    public Optional<LayoutStore.StoredLayout> rebuild(final String bundle, final String layout) {
        return Optional.ofNullable(rebuilds.get(bundle + "/" + layout));
    }

    /**
     * Returns the attachment an {@code upload://<id>} address shows, empty for another address or an image not stored.
     * It is sent with the message, which names it as {@code attachment://} followed by its name.
     */
    public Optional<FileUpload> upload(final String address) {
        return LayoutStore.LayoutImage.idOf(address)
                .flatMap(store::image)
                .map(image -> FileUpload.fromData(image.bytes(), name(image)));
    }

    /** The file name an image is sent under, its id with the extension of its type. */
    static String name(final LayoutStore.LayoutImage image) {
        return "upload-" + image.id() + "."
                + image.mediaType().substring("image/".length()).replace("jpeg", "jpg");
    }
}
