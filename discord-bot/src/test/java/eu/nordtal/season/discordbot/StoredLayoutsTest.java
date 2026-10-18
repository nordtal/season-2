package eu.nordtal.season.discordbot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.layout.LayoutStore;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.dv8tion.jda.api.utils.FileUpload;
import org.junit.jupiter.api.Test;

/** A stored image as the bot sends it beside a layout that shows it. */
class StoredLayoutsTest {

    /** A store that holds one JPEG as image 7. */
    private static final LayoutStore STORE = new LayoutStore() {
        @Override
        public List<StoredLayout> layouts(final Collection<String> bundles) {
            return List.of();
        }

        @Override
        public void change(final StoredLayout layout, final Actor actor) {}

        @Override
        public void remove(final String bundle, final String layout) {}

        @Override
        public long upload(final String mediaType, final byte[] bytes, final Actor actor) {
            return 7;
        }

        @Override
        public Optional<LayoutImage> image(final long id) {
            return id == 7 ? Optional.of(new LayoutImage(7, "image/jpeg", new byte[] {1, 2})) : Optional.empty();
        }
    };

    @Test
    void anUploadedImageIsSentAsAnAttachmentNamedByItsIdAndType() throws IOException {
        final FileUpload upload = new StoredLayouts(STORE).upload("upload://7").orElseThrow();

        assertEquals("upload-7.jpg", upload.getName());
        try (InputStream data = upload.getData()) {
            assertArrayEquals(new byte[] {1, 2}, data.readAllBytes());
        }
    }

    @Test
    void anAddressThatIsNoUploadOrAnImageNotStoredIsNoAttachment() {
        final StoredLayouts layouts = new StoredLayouts(STORE);

        assertTrue(layouts.upload("attachment://contribution.png").isEmpty());
        assertTrue(layouts.upload("upload://8").isEmpty());
        assertTrue(layouts.rebuild("discord-bot", "contribution").isEmpty());
    }
}
