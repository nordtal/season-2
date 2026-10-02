package eu.nordtal.s2.steward.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A restore is a run the agent carries out; Steward only writes the row, and only once the name is typed back. */
class RestoreRunTest extends WebTestSupport {

    private static final String ARCHIVE = "nordtal-s2_mc-smp-20261001T044500Z.tar.zst";
    private static final String DUMP = "nordtal-20261001T044500Z.dump";

    @BeforeEach
    void archivesOnTheDisk() throws Exception {
        Files.writeString(agent.backups.resolve(ARCHIVE), "a world");
        Files.writeString(agent.backups.resolve(DUMP), "a database");
    }

    @AfterEach
    void forget() throws Exception {
        Files.deleteIfExists(agent.backups.resolve(ARCHIVE));
        Files.deleteIfExists(agent.backups.resolve(DUMP));
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("delete from steward_inbox where kind = 'RESTORE'");
        }
    }

    @Test
    void aVolumeArchiveConfirmedByItsVolumeIsARestoreRunNamingIt() throws Exception {
        final HttpResponse<String> asked = restore(ARCHIVE, "nordtal-s2_mc-smp");

        assertEquals(202, asked.statusCode(), asked.body());
        assertEquals(
                1,
                count("select count(*) from steward_inbox where kind = 'RESTORE' and status = 'PENDING'"
                        + " and payload ->> 'archive' = '" + ARCHIVE + "' and actor_id = '1'"));
    }

    @Test
    void aDumpIsConfirmedByTheDatabasesName() throws Exception {
        assertEquals(400, restore(DUMP, "database").statusCode());
        assertEquals(202, restore(DUMP, "nordtal").statusCode());
    }

    @Test
    void aWrongOrMissingConfirmationWritesNoRow() throws Exception {
        assertEquals(400, restore(ARCHIVE, "nordtal-s2_mc-smp-plugins").statusCode());
        assertEquals(400, post("/api/backups/" + ARCHIVE + "/restore", "{}").statusCode());
        assertEquals(0, count("select count(*) from steward_inbox where kind = 'RESTORE'"));
    }

    @Test
    void anArchiveThatIsNotOnTheDiskIsA404() throws Exception {
        final String gone =
                Path.of("nordtal-s2_mc-smp-20250101T000000Z.tar.zst").toString();

        assertEquals(404, restore(gone, "nordtal-s2_mc-smp").statusCode());
        assertEquals(0, count("select count(*) from steward_inbox where kind = 'RESTORE'"));
    }

    private HttpResponse<String> restore(final String archive, final String confirm) throws Exception {
        return post("/api/backups/" + archive + "/restore", "{\"confirm\":\"" + confirm + "\"}");
    }
}
