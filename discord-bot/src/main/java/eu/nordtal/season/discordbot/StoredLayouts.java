package eu.nordtal.season.discordbot;

import eu.nordtal.season.database.layout.LayoutStore;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.messages.spec.layout.Layout;
import eu.nordtal.season.messages.spec.layout.LayoutMerge;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.dv8tion.jda.api.utils.FileUpload;

public final class StoredLayouts {

    private final LayoutStore store;
    private volatile Map<String, LayoutStore.StoredLayout> rebuilds = Map.of();

    public StoredLayouts(final LayoutStore store) {
        this.store = store;
    }

    void follow(final Collection<String> bundles, final SignalHub hub) {
        store.follow(bundles, hub, this::hold);
    }

    void hold(final List<LayoutStore.StoredLayout> read) {
        final Map<String, LayoutStore.StoredLayout> byId = new LinkedHashMap<>();
        read.forEach(rebuild -> byId.put(rebuild.bundle() + "/" + rebuild.layout(), rebuild));
        rebuilds = Map.copyOf(byId);
    }

    /**
     * Returns the stored rebuild of {@code declared}, else {@code declared}.
     * A rebuild written over another declaration than this jar's is merged into this jar's.
     */
    public Layout applied(final Layout declared) {
        final LayoutStore.StoredLayout rebuild = rebuilds.get(declared.bundle() + "/" + declared.name());
        return rebuild == null
                ? declared
                : LayoutMerge.merge(declared, rebuild.parts(), rebuild.links(), rebuild.replaced())
                        .layout();
    }

    /**
     * Returns the attachment that an {@code upload://<id>} address shows.
     * It is empty for another address or an image that is not stored.
     * The message names the file as {@code attachment://} followed by its name.
     */
    public Optional<FileUpload> upload(final String address) {
        return LayoutStore.LayoutImage.idOf(address)
                .flatMap(store::image)
                .map(image -> FileUpload.fromData(image.bytes(), name(image)));
    }

    static String name(final LayoutStore.LayoutImage image) {
        return "upload-" + image.id() + "."
                + image.mediaType().substring("image/".length()).replace("jpeg", "jpg");
    }
}
