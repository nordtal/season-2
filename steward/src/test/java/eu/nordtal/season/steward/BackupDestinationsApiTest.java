package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import eu.nordtal.season.common.SecretBox;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.internalapi.backup.BackupDestinationWire;
import eu.nordtal.season.steward.backup.Destinations;
import eu.nordtal.season.steward.backup.GoogleDrive;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Chooses a Google account for a backup series, hands its secrets to the series alone and keeps how it copied. */
class BackupDestinationsApiTest extends WebTestSupport {

    private static Destinations store() {
        return new Destinations(
                WebFixture.postgres.dataSource(),
                SecretBox.of(config.backupKey()).orElseThrow());
    }

    private static UUID connected(final String subject) {
        return store().connect(
                        new GoogleDrive.Identity(subject, subject + "@example.com"),
                        new GoogleDrive.Folder("folder-" + subject, GoogleDrive.FOLDER),
                        "refresh-" + subject);
    }

    private static HttpResponse<String> internal(
            final String method,
            final String series,
            final String path,
            final @Nullable String token,
            final String body)
            throws Exception {
        final HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + webPort + BackupDestinationWire.of(path, series)))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return java.net.http.HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> credentials(final String series, final @Nullable String token)
            throws Exception {
        return internal("GET", series, BackupDestinationWire.CREDENTIALS, token, "");
    }

    @BeforeEach
    void noAccountIsConnected() throws Exception {
        sql("UPDATE backup_series_state SET account_id = NULL, last_copied_at = NULL, last_snapshot_id = NULL,"
                + " last_bytes = NULL, last_error = NULL, last_failed_at = NULL");
        sql("DELETE FROM backup_repositories");
        sql("DELETE FROM backup_google_accounts");
    }

    @Test
    void aSeriesWithoutAnAccountGetsNoContentAndAWrongTokenGetsNothing() throws Exception {
        final HttpResponse<String> none = credentials(BackupDestinationWire.STACK, WebFixture.AGENT_TOKEN);
        assertEquals(204, none.statusCode(), none.body());
        assertEquals(Optional.of("no-store"), none.headers().firstValue("Cache-Control"));

        assertEquals(401, credentials(BackupDestinationWire.STACK, null).statusCode());
        assertEquals(
                401,
                credentials(BackupDestinationWire.STACK, WebFixture.PLANE_BACKUP_TOKEN)
                        .statusCode());
        assertEquals(
                401,
                credentials(BackupDestinationWire.PLANE, WebFixture.AGENT_TOKEN).statusCode());
        assertEquals(404, credentials("worlds", WebFixture.AGENT_TOKEN).statusCode());
    }

    @Test
    void aChosenAccountHandsItsSecretsToItsSeriesAndTheRecoveryFileOnce() throws Exception {
        final UUID account = connected("subject-1");

        final HttpResponse<String> chosen = put(
                "/api/backup-destinations/series/" + BackupDestinationWire.PLANE,
                "{\"accountId\":\"" + account + "\"}");
        assertEquals(204, chosen.statusCode(), chosen.body());
        journalledBy(WebFixture.memberId.get(), "CHOOSE_BACKUP_ACCOUNT");

        final HttpResponse<String> plane = credentials(BackupDestinationWire.PLANE, WebFixture.PLANE_BACKUP_TOKEN);
        assertEquals(200, plane.statusCode(), plane.body());
        assertEquals(Optional.of("no-store"), plane.headers().firstValue("Cache-Control"));
        final JsonObject secrets = GSON.fromJson(plane.body(), JsonObject.class);
        assertEquals(account.toString(), secrets.get("accountId").getAsString());
        assertEquals("folder-subject-1", secrets.get("folderId").getAsString());
        assertEquals("refresh-subject-1", secrets.get("refreshToken").getAsString());
        assertEquals(config.googleDrive().clientId(), secrets.get("clientId").getAsString());
        assertTrue(secrets.get("subpath").getAsString().startsWith("plane-"), plane.body());
        assertEquals(64, secrets.get("resticPassword").getAsString().length());
        assertEquals(
                204,
                credentials(BackupDestinationWire.STACK, WebFixture.AGENT_TOKEN).statusCode());

        final String overview = get("/api/backup-destinations").body();
        assertFalse(overview.contains("refresh-subject-1"), overview);
        assertFalse(overview.contains(secrets.get("resticPassword").getAsString()), overview);

        final String recovery = "/api/backup-destinations/accounts/" + account + "/repositories/plane/recovery";
        final HttpResponse<String> file = post(recovery, "");
        assertEquals(200, file.statusCode(), file.body());
        assertTrue(file.body().contains(secrets.get("resticPassword").getAsString()), file.body());
        assertEquals(410, post(recovery, "").statusCode(), "a recovery file is handed out once");
    }

    @Test
    void aReportIsKeptForTheActiveAccountAndDroppedForAnother() throws Exception {
        final UUID first = connected("subject-1");
        final UUID second = connected("subject-2");
        assertTrue(store().choose(BackupDestinationWire.STACK, first));

        final Instant copiedAt = Instant.parse("2026-11-23T03:00:00Z");
        final String ok =
                Json.encode(new BackupDestinationWire.Report(first.toString(), true, copiedAt, "4f1c2d", 400L, null));
        final HttpResponse<String> kept =
                internal("POST", BackupDestinationWire.STACK, BackupDestinationWire.REPORT, WebFixture.AGENT_TOKEN, ok);
        assertEquals(204, kept.statusCode(), kept.body());
        final Destinations.BackupSeries stack = stack();
        assertEquals(copiedAt, stack.lastCopiedAt());
        assertEquals("4f1c2d", stack.lastSnapshotId());

        assertTrue(store().choose(BackupDestinationWire.STACK, second));
        assertEquals(null, stack().lastCopiedAt(), "a copy to the first account is not the second one's");
        final String late = Json.encode(
                new BackupDestinationWire.Report(first.toString(), false, null, null, null, "the copy failed"));
        assertEquals(
                204,
                internal(
                                "POST",
                                BackupDestinationWire.STACK,
                                BackupDestinationWire.REPORT,
                                WebFixture.AGENT_TOKEN,
                                late)
                        .statusCode());
        assertEquals(null, stack().lastError(), "a report of the account before the switch was kept");
        assertEquals(
                401,
                internal("POST", BackupDestinationWire.STACK, BackupDestinationWire.REPORT, null, ok)
                        .statusCode());
    }

    @Test
    void aDisconnectStopsEverySeriesAndKeepsTheRepositories() {
        final UUID account = connected("subject-1");
        assertTrue(store().choose(BackupDestinationWire.STACK, account));
        assertTrue(store().choose(BackupDestinationWire.PLANE, account));

        assertTrue(store().disconnect(account));

        assertEquals(Optional.empty(), store().credentials(BackupDestinationWire.STACK, "id", "secret"));
        assertEquals(null, stack().accountId());
        assertEquals(2, store().repositories().size());
        assertFalse(store().choose(BackupDestinationWire.STACK, account), "a disconnected account is chosen");
        final UUID again = connected("subject-1");
        assertEquals(account, again, "a reconnected account is a new row");
        assertTrue(store().choose(BackupDestinationWire.STACK, again));
        assertEquals(2, store().repositories().size(), "a reconnect made a second repository");
    }

    @Test
    void aSealedSecretMovedToAnotherRowDoesNotOpen() throws Exception {
        final UUID first = connected("subject-1");
        final UUID second = connected("subject-2");
        sql("UPDATE backup_google_accounts SET refresh_token = (SELECT refresh_token FROM backup_google_accounts"
                + " WHERE id = '" + first + "') WHERE id = '" + second + "'");
        assertNotEquals(first, second);

        final RuntimeException refused = assertThrows(RuntimeException.class, () -> store().refreshToken(second));

        assertFalse(String.valueOf(refused.getMessage()).contains("refresh-subject-1"), refused.getMessage());
    }

    private static Destinations.BackupSeries stack() {
        return store().series().stream()
                .filter(series -> series.series().equals(BackupDestinationWire.STACK))
                .findFirst()
                .orElseThrow();
    }
}
