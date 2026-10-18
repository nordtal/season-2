package eu.nordtal.season.discordbot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.layout.LayoutStore;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.spec.layout.Element;
import eu.nordtal.season.messages.spec.layout.Layout;
import eu.nordtal.season.messages.spec.layout.LayoutSchema;
import eu.nordtal.season.messages.spec.layout.Line;
import eu.nordtal.season.messages.spec.layout.Part;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import net.dv8tion.jda.api.utils.FileUpload;
import org.junit.jupiter.api.Test;

/** A stored rebuild as the bot draws it, and a stored image as it sends it beside a layout that shows it. */
class StoredLayoutsTest {

    private static final LayoutStore.LayoutImage JPEG = new LayoutStore.LayoutImage(7, "image/jpeg", new byte[] {1, 2});

    private static final Layout DECLARED = Layout.discord(
            "access",
            "welcome",
            "Join",
            List.of(Element.button("join", "access/join")),
            Part.text(Line.title("access/welcome")),
            Part.row("join"));

    private static LayoutStore.StoredLayout rebuild(final String replaced) {
        return new LayoutStore.StoredLayout(
                "access",
                "welcome",
                List.of(Part.row("join"), Part.container(Tone.BRAND, Part.text(Line.text("access/welcome")))),
                List.of(),
                replaced);
    }

    @Test
    void anUploadedImageIsSentAsAnAttachmentNamedByItsIdAndType() throws IOException {
        final FileUpload upload = StoredLayoutsStandIn.holding(List.of(), List.of(JPEG))
                .upload("upload://7")
                .orElseThrow();

        assertEquals("upload-7.jpg", upload.getName());
        try (InputStream data = upload.getData()) {
            assertArrayEquals(new byte[] {1, 2}, data.readAllBytes());
        }
    }

    @Test
    void anAddressThatIsNoUploadOrAnImageNotStoredIsNoAttachment() {
        final StoredLayouts layouts = StoredLayoutsStandIn.holding(List.of(), List.of(JPEG));

        assertTrue(layouts.upload("attachment://contribution.png").isEmpty());
        assertTrue(layouts.upload("upload://8").isEmpty());
    }

    @Test
    void aRebuildIsDrawnOnlyOverTheDeclarationItWasWrittenOver() {
        final LayoutStore.StoredLayout current = rebuild(LayoutSchema.hash(DECLARED));

        assertEquals(
                current.parts(),
                StoredLayoutsStandIn.holding(List.of(current), List.of())
                        .applied(DECLARED)
                        .parts());
        assertEquals(
                DECLARED,
                StoredLayoutsStandIn.holding(List.of(rebuild("b".repeat(64))), List.of())
                        .applied(DECLARED),
                "a rebuild over another release's declaration is set aside");
        assertEquals(DECLARED, StoredLayoutsStandIn.none().applied(DECLARED));
    }
}
