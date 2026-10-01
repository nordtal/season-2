package eu.nordtal.s2.steward.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.settings.SettingsException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What {@code steward.yml} refuses, and what it drops.
 *
 * A live PGDATA in {@code backup.volumes} is refused; a retired key costs a WARN and a {@code .bak}, not a start.
 */
class StewardSettingsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(StewardSettingsTest.class);

    @TempDir
    Path directory;

    @Test
    void aFreshFileBacksUpTheFourVolumesThatCannotBeRebuiltAndNeverPgdata() throws Exception {
        final StewardSpec config = StewardSettings.steward(directory, LOGGER).get();
        final java.util.List<String> volumes = config.backup().volumes();

        // Nordtal is a hand-built world in no repository or release; plugins volumes hold every hand-edited config.
        assertTrue(volumes.contains("nordtal-s2_mc-smp"), volumes.toString());
        assertTrue(volumes.contains("nordtal-s2_mc-smp-plugins"), volumes.toString());
        assertTrue(
                volumes.stream().noneMatch(volume -> volume.endsWith("postgres-data")),
                "a snapshot of a live PGDATA fails at RESTORE and nowhere else: " + volumes);

        // The database is DUMPED, not snapshotted, straight into backup.output-root, so it needs no volume here.
        assertTrue(
                volumes.stream().noneMatch(volume -> volume.contains("dumps") || volume.contains("backups")),
                "the backups are not a thing to back up: " + volumes);

        // hunger-games has no world worth saving but a plugins/ volume worth snapshotting; the proxy needs neither.
        assertEquals(
                java.util.List.of(
                        eu.nordtal.s2.steward.plan.Topology.SMP, eu.nordtal.s2.steward.plan.Topology.DISCORD_BOT),
                config.backup().stopServices());
        assertFalse(
                config.backup().volumes().stream()
                        .anyMatch(volume -> volume.contains("proxy") || volume.contains("limbo")),
                "proxy and limbo left the backup on 2026-09-20 and a restart writes everything" + " they hold: "
                        + config.backup().volumes());

        // A run ending FAILED mentions the admin role through UpdateFeed, so a half-hour outage is not silent.
        assertEquals(30, config.backup().patienceMinutes());
    }

    @Test
    void listingPostgresDataIsRefusedByNameNotWarnedAbout() throws Exception {
        java.nio.file.Files.writeString(directory.resolve("steward.yml"), """
                backup:
                  volumes:
                    - 'nordtal-s2_postgres-data'
                """);

        final SettingsException error =
                assertThrows(SettingsException.class, () -> StewardSettings.steward(directory, LOGGER));

        final String message = String.valueOf(error.getMessage() + error.getCause());
        assertTrue(
                message.contains("postgres-dumps"),
                "and it names the volume that should have been there instead: " + message);
    }

    @Test
    void aVolumeListWithoutTheWorldIsRefusedBecauseTheWorldCannotBeRebuilt() throws Exception {
        // A run reports DONE for saving what this list names; dropping mc-smp would still report DONE without it.
        java.nio.file.Files.writeString(directory.resolve("steward.yml"), """
                backup:
                  volumes:
                    - 'nordtal-s2_bot-config'
                """);

        final SettingsException error =
                assertThrows(SettingsException.class, () -> StewardSettings.steward(directory, LOGGER));

        final String message = String.valueOf(error.getMessage() + error.getCause());
        assertTrue(
                message.contains("in no repository and in no"),
                "and it says what the missing volume is load-bearing for: " + message);
    }

    @Test
    void aDeployedStewardYmlStillCarryingRetiredKeysLosesThemAndStarts() throws Exception {
        // A RETIRED key gets a WARN and a .bak, then the process starts; nobody must re-declare one as a no-op.
        StewardSettings.steward(directory, LOGGER);

        final Path file = directory.resolve("steward.yml");
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8) + """

                minecraft-version: '26.2'
                velocity-version: '4.1.1'
                paper-build: latest
                velocity-build: '24'
                arcane:
                  base-url: 'https://arcane.example.com'
                  api-key: 'token'
                """, StandardCharsets.UTF_8);

        final StewardSpec config = StewardSettings.steward(directory, LOGGER).get();
        assertEquals("nordtal/season-2", config.seasonRepo(), "steward refused to start");

        final String written = Files.readString(file, StandardCharsets.UTF_8);
        for (final String retired :
                new String[] {"minecraft-version", "velocity-version", "paper-build", "velocity-build", "arcane"}) {
            assertFalse(
                    written.contains(retired),
                    "steward.yml still carries '" + retired + "' after a load. Either it was"
                            + " re-declared - which makes an operator believe a value nothing"
                            + " reads - or jcore stopped trimming retired keys.");
        }
        assertTrue(
                Files.isRegularFile(directory.resolve("steward.yml.bak")),
                "the old content is not in a .bak, so an operator who wanted those lines back has"
                        + " nowhere to read them from");
    }

    @Test
    void aFreshFileHasNoBunqCredentialsAndThatIsAValidDeployment() throws Exception {
        final StewardSpec.BunqSpec bunq =
                StewardSettings.steward(directory, LOGGER).get().bunq();

        // Empty is the default and the load succeeds: a season with no bank account must still be able to start.
        assertEquals("", bunq.apiKey());
        assertEquals("", bunq.accountId());
        assertEquals(30, bunq.pollIntervalSeconds());
        assertEquals(50, bunq.recentPaymentCount());
        assertEquals("", bunq.watermark(), "the first start stamps its own instant; see Watermark");
    }

    @Test
    void halfABunqCredentialIsRefused() throws Exception {
        // A key with no matching account is always a setup stopped midway, and the message has to say what happened.
        Files.writeString(directory.resolve("steward.yml"), """
                bunq:
                  api-key: 'a-key'
                  account-id: ''
                """);

        final SettingsException error =
                assertThrows(SettingsException.class, () -> StewardSettings.steward(directory, LOGGER));

        final String message = String.valueOf(error.getMessage()) + error.getCause();
        assertTrue(message.contains("both api-key and account-id or neither"), message);
    }

    @Test
    void aNonNumericBunqAccountIdIsCaughtAtStartupNotInsideAPoll() throws Exception {
        Files.writeString(directory.resolve("steward.yml"), """
                bunq:
                  api-key: 'a-key'
                  account-id: 'NL91BUNQ0417164300'
                """);

        final SettingsException error =
                assertThrows(SettingsException.class, () -> StewardSettings.steward(directory, LOGGER));

        // A bad IBAN parses as a long id and must not surface as a bare NumberFormatException at startup.
        final String message = String.valueOf(error.getMessage()) + error.getCause();
        assertTrue(message.contains("must be a number"), message);
    }

    @Test
    void aWatermarkOverrideThatIsNotAnInstantIsRefused() throws Exception {
        // An unreadable watermark must not surface as a poll that books nothing, indistinguishable from a quiet bank.
        Files.writeString(directory.resolve("steward.yml"), """
                bunq:
                  watermark: '1 September 2026'
                """);

        final SettingsException error =
                assertThrows(SettingsException.class, () -> StewardSettings.steward(directory, LOGGER));

        final String message = String.valueOf(error.getMessage()) + error.getCause();
        assertTrue(message.contains("ISO-8601"), message);
    }

    @Test
    void aCompleteBunqBlockLoadsAndTheIdKeepsItsOwnText() throws Exception {
        Files.writeString(directory.resolve("steward.yml"), """
                bunq:
                  api-key: 'a-key'
                  account-id: '987654'
                  poll-interval-seconds: 45
                  recent-payment-count: 10
                  watermark: '2026-09-01T00:00:00Z'
                """);

        final StewardSpec.BunqSpec bunq =
                StewardSettings.steward(directory, LOGGER).get().bunq();
        assertEquals("987654", bunq.accountId());
        assertEquals(45, bunq.pollIntervalSeconds());
        assertEquals(10, bunq.recentPaymentCount());
        assertEquals("2026-09-01T00:00:00Z", bunq.watermark());
    }
}
