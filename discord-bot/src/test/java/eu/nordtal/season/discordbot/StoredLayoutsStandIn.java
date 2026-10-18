package eu.nordtal.season.discordbot;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.layout.LayoutStore;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Stored layouts held in memory, for a test that draws a layout without a database. */
public final class StoredLayoutsStandIn {

    private StoredLayoutsStandIn() {}

    /** Returns stored layouts that hold no rebuild and no image. */
    public static StoredLayouts none() {
        return holding(List.of(), List.of());
    }

    /** Returns stored layouts that hold these rebuilds and images. */
    public static StoredLayouts holding(
            final List<LayoutStore.StoredLayout> rebuilds, final List<LayoutStore.LayoutImage> images) {
        final StoredLayouts layouts = new StoredLayouts(new LayoutStore() {
            @Override
            public List<StoredLayout> layouts(final Collection<String> bundles) {
                return rebuilds;
            }

            @Override
            public void change(final StoredLayout layout, final Actor actor) {
                throw new UnsupportedOperationException("the stand-in is read only");
            }

            @Override
            public void remove(final String bundle, final String layout) {
                throw new UnsupportedOperationException("the stand-in is read only");
            }

            @Override
            public long upload(final String mediaType, final byte[] bytes, final Actor actor) {
                throw new UnsupportedOperationException("the stand-in is read only");
            }

            @Override
            public Optional<LayoutImage> image(final long id) {
                return images.stream().filter(image -> image.id() == id).findFirst();
            }
        });
        layouts.hold(rebuilds);
        return layouts;
    }
}
