package eu.nordtal.season.discordbot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.DocumentSpec;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import net.dv8tion.jda.api.utils.FileUpload;
import org.junit.jupiter.api.Test;

class StoredDocumentsTest {

    private static final DocumentStore.Image JPEG = new DocumentStore.Image(7, "image/jpeg", new byte[] {1, 2});

    @Test
    void anUploadedImageIsSentAsAnAttachmentNamedByItsIdAndType() throws IOException {
        final FileUpload upload = StoredDocumentsStandIn.holding(List.of(), List.of(JPEG))
                .upload("upload://7")
                .orElseThrow();

        assertEquals("upload-7.jpg", upload.getName());
        try (InputStream data = upload.getData()) {
            assertArrayEquals(new byte[] {1, 2}, data.readAllBytes());
        }
    }

    @Test
    void anAddressThatIsNoUploadOrAnImageNotStoredIsNoAttachment() {
        final StoredDocuments documents = StoredDocumentsStandIn.holding(List.of(), List.of(JPEG));

        assertTrue(documents.upload("attachment://contribution.png").isEmpty());
        assertTrue(documents.upload("upload://8").isEmpty());
    }

    @Test
    void anAdminsDocumentIsShownInPlaceOfThePackagedOne() {
        final String packaged =
                StoredDocumentsStandIn.none().applied(Replies.LINK_SUCCESS).hash();
        final Document written = Document.decode("""
                {"parts": [{"type": "TEXT", "lines": [{"text": {"en": "Linked.", "de": "Verknüpft."}}]}]}""");

        assertEquals(
                written,
                StoredDocumentsStandIn.holding(
                                List.of(new DocumentStore.StoredDocument(
                                        StoredDocuments.NAMESPACE, "link-success", written, packaged)),
                                List.of())
                        .applied(Replies.LINK_SUCCESS)
                        .document());
        assertNotEquals(
                written,
                StoredDocumentsStandIn.holding(
                                List.of(new DocumentStore.StoredDocument("other", "link-success", written, packaged)),
                                List.of())
                        .applied(Replies.LINK_SUCCESS)
                        .document(),
                "a document of another namespace is not the bot's");
    }

    @Test
    void aDocumentTheJarDoesNotPackageIsAFault() {
        assertThrows(
                IllegalStateException.class,
                () -> StoredDocumentsStandIn.none()
                        .applied(DocumentSpec.message(StoredDocuments.NAMESPACE, "nowhere", "Nowhere")
                                .titled("Nowhere")));
    }
}
