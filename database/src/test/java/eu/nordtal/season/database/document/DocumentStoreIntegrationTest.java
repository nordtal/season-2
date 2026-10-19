package eu.nordtal.season.database.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.messages.document.Document;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.Test;

/** Stores admins' documents and their images against PostgreSQL and the real migrations. */
class DocumentStoreIntegrationTest {

    private static final Actor ADMIN = Actor.person(DiscordId.of("123456789012345678"));
    private static final String HASH = "a".repeat(64);

    private static DocumentStore.StoredDocument written(final long image, final String title) {
        return new DocumentStore.StoredDocument(
                "discord", "contribution", Document.decode("""
                        {"parts": [{"type": "CONTAINER", "accent": "#4a63d8", "parts": [
                          {"type": "SECTION", "thumbnail": "upload://%d", "parts": [
                            {"type": "TEXT", "lines": [{"text": {"en": "%s", "de": ["Moin", "Hallo"]}, "style": "TITLE"}]}]},
                          {"type": "GALLERY", "media": ["attachment://contribution.png", "https://nordtal.eu/a.png"]}]},
                          {"type": "ROW", "items": [{"slot": "buy", "label": {"en": "Buy"}, "when": "open"}]}]}
                        """.formatted(image, title)), HASH);
    }

    @Test
    void stewardWritesADocumentAndTheBotReadsTheSameDocumentBack() {
        final TestDatabase database = TestDatabase.fresh();
        final DocumentStore steward = DocumentStore.using(database.dataSourceAs(DatabaseRole.STEWARD));
        final long image = steward.upload("image/png", new byte[] {1, 2, 3}, ADMIN);
        steward.change(written(image, "Hi"), ADMIN);
        steward.change(written(image, "Hello"), ADMIN);

        assertEquals(
                List.of(written(image, "Hello")),
                DocumentStore.using(database.dataSourceAs(DatabaseRole.DISCORD_BOT))
                        .documents(Set.of("discord", "other")),
                "a second save replaced the first");
    }

    @Test
    void everySaveAndEveryReturnToThePackagedDocumentIsKeptNewestFirst() {
        final TestDatabase database = TestDatabase.fresh();
        final DocumentStore store = DocumentStore.using(database.dataSourceAs(DatabaseRole.STEWARD));
        final long image = store.upload("image/png", new byte[] {1}, ADMIN);
        store.change(written(image, "Hi"), ADMIN);
        store.change(written(image, "Hello"), Actor.STEWARD);
        store.remove("discord", "contribution", ADMIN);

        final List<DocumentStore.Version> versions = store.versions("discord", "contribution");
        assertEquals(
                java.util.Arrays.asList(null, written(image, "Hello"), written(image, "Hi")),
                versions.stream().map(DocumentStore.Version::document).toList());
        assertEquals(
                List.of(ADMIN, Actor.STEWARD, ADMIN),
                versions.stream().map(DocumentStore.Version::actor).toList());
        assertEquals(List.of(), store.versions("discord", "other"));
    }

    @Test
    void onlyStewardReadsTheVersions() {
        final TestDatabase database = TestDatabase.fresh();
        assertThrows(
                UnableToExecuteStatementException.class,
                () -> DocumentStore.using(database.dataSourceAs(DatabaseRole.DISCORD_BOT))
                        .versions("discord", "contribution"));
    }

    @Test
    void theSameImageIsStoredOnceAndReadBackWithItsMediaType() {
        final DocumentStore store = DocumentStore.using(TestDatabase.fresh().dataSourceAs(DatabaseRole.STEWARD));
        final long first = store.upload("image/webp", new byte[] {9, 8}, ADMIN);

        assertEquals(first, store.upload("image/webp", new byte[] {9, 8}, Actor.STEWARD));
        final DocumentStore.Image read = store.image(first).orElseThrow();
        assertEquals("image/webp", read.mediaType());
        assertArrayEquals(new byte[] {9, 8}, read.bytes());
        assertTrue(store.image(first + 1).isEmpty());
    }

    @Test
    void aDocumentThatShowsAnImageNobodyUploadedIsRefusedAndARemovedOneIsGone() {
        final DocumentStore store = DocumentStore.using(TestDatabase.fresh().dataSourceAs(DatabaseRole.STEWARD));
        assertThrows(IllegalArgumentException.class, () -> store.change(written(41, "Hi"), ADMIN));

        store.change(written(store.upload("image/png", new byte[] {1}, ADMIN), "Hi"), ADMIN);
        store.remove("discord", "contribution", ADMIN);
        assertEquals(List.of(), store.documents(Set.of("discord")));
    }

    @Test
    void aChangeSignalsItsNamespace() throws SQLException {
        final TestDatabase database = TestDatabase.fresh();
        try (Connection listener = database.dataSource().getConnection();
                Statement statement = listener.createStatement()) {
            statement.execute("LISTEN nordtal_messages");
            DocumentStore.using(database.dataSourceAs(DatabaseRole.STEWARD)).remove("discord", "contribution", ADMIN);
            final org.postgresql.PGNotification[] notes =
                    listener.unwrap(org.postgresql.PGConnection.class).getNotifications(5000);
            assertEquals("discord", notes[0].getParameter());
        }
    }

    @Test
    void aServerCannotWriteADocumentOrAnImage() {
        final DocumentStore smp = DocumentStore.using(TestDatabase.fresh().dataSourceAs(DatabaseRole.SMP));
        assertThrows(UnableToExecuteStatementException.class, () -> smp.upload("image/png", new byte[] {1}, ADMIN));
        assertThrows(UnableToExecuteStatementException.class, () -> smp.remove("discord", "x", ADMIN));
    }
}
