package eu.nordtal.season.database.layout;

import static eu.nordtal.season.messages.spec.layout.Line.heading;
import static eu.nordtal.season.messages.spec.layout.Line.list;
import static eu.nordtal.season.messages.spec.layout.Line.text;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.spec.layout.Element;
import eu.nordtal.season.messages.spec.layout.Line;
import eu.nordtal.season.messages.spec.layout.Part;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.Test;

/** Stores rebuilt layouts and their images against a real PostgreSQL running the real migrations. */
class LayoutStoreIntegrationTest {

    private static final Actor ADMIN = Actor.person(DiscordId.of("123456789012345678"));
    private static final String HASH = "a".repeat(64);

    private static LayoutStore.StoredLayout rebuild(final long image) {
        return new LayoutStore.StoredLayout(
                "discord-bot",
                "contribution",
                List.of(
                        Part.container(
                                Tone.BRAND,
                                Part.text(Line.added(Line.Style.TITLE, Map.of("en", "Hi", "de", "Moin"))),
                                Part.sectionWithThumbnail(
                                        "upload://" + image, Part.text(heading("discord-bot/prices"), list("tiers"))),
                                Part.separator(),
                                Part.gallery("attachment://contribution.png", "https://nordtal.eu/a.png")),
                        Part.languages("each", Part.text(text("discord-bot/how"))),
                        Part.row("buy", "site")),
                List.of(Element.link("site", "https://nordtal.eu", Map.of("en", "Site", "de", "Seite"))),
                HASH);
    }

    @Test
    void stewardWritesARebuildAndTheBotReadsTheSameTreeBack() {
        final TestDatabase database = TestDatabase.fresh();
        final LayoutStore steward = LayoutStore.using(database.dataSourceAs(DatabaseRole.STEWARD));
        final long image = steward.upload("image/png", new byte[] {1, 2, 3}, ADMIN);
        steward.change(rebuild(image), ADMIN);
        steward.change(rebuild(image), ADMIN);

        assertEquals(
                List.of(rebuild(image)),
                LayoutStore.using(database.dataSourceAs(DatabaseRole.DISCORD_BOT))
                        .layouts(Set.of("discord-bot", "access")),
                "a second save replaced the first");
    }

    @Test
    void theSameImageIsStoredOnceAndReadBackWithItsMediaType() {
        final LayoutStore store = LayoutStore.using(TestDatabase.fresh().dataSourceAs(DatabaseRole.STEWARD));
        final long first = store.upload("image/webp", new byte[] {9, 8}, ADMIN);

        assertEquals(first, store.upload("image/webp", new byte[] {9, 8}, Actor.STEWARD));
        final LayoutStore.LayoutImage read = store.image(first).orElseThrow();
        assertEquals("image/webp", read.mediaType());
        assertArrayEquals(new byte[] {9, 8}, read.bytes());
        assertTrue(store.image(first + 1).isEmpty());
    }

    @Test
    void aRebuildThatShowsAnImageNobodyUploadedIsRefusedAndARemovedOneIsGone() {
        final LayoutStore store = LayoutStore.using(TestDatabase.fresh().dataSourceAs(DatabaseRole.STEWARD));
        assertThrows(IllegalArgumentException.class, () -> store.change(rebuild(41), ADMIN));

        store.change(rebuild(store.upload("image/png", new byte[] {1}, ADMIN)), ADMIN);
        store.remove("discord-bot", "contribution");
        assertEquals(List.of(), store.layouts(Set.of("discord-bot")));
    }

    @Test
    void aChangeSignalsItsBundle() throws SQLException {
        final TestDatabase database = TestDatabase.fresh();
        try (Connection listener = database.dataSource().getConnection();
                Statement statement = listener.createStatement()) {
            statement.execute("LISTEN nordtal_messages");
            LayoutStore.using(database.dataSourceAs(DatabaseRole.STEWARD)).remove("discord-bot", "contribution");
            final org.postgresql.PGNotification[] notes =
                    listener.unwrap(org.postgresql.PGConnection.class).getNotifications(5000);
            assertEquals("discord-bot", notes[0].getParameter());
        }
    }

    @Test
    void aServerCannotWriteALayoutOrAnImage() {
        final LayoutStore smp = LayoutStore.using(TestDatabase.fresh().dataSourceAs(DatabaseRole.SMP));
        assertThrows(UnableToExecuteStatementException.class, () -> smp.upload("image/png", new byte[] {1}, ADMIN));
        assertThrows(UnableToExecuteStatementException.class, () -> smp.remove("smp", "x"));
    }
}
