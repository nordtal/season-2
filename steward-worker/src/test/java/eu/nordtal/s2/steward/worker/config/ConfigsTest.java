package eu.nordtal.s2.steward.worker.config;

import eu.nordtal.jcore.config.exception.ConfigValidationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code steward.yml} refuses, and what it drops.
 * <p>
 * Most of this spec is a value whose default is the real one, so a fresh file is correct and there
 * is nothing to catch. What is worth a test is the two places where a wrong value does its damage
 * somewhere else entirely: {@code backup.volumes} pointing at a live PGDATA, which fails at
 * {@code pg_restore} months later rather than here, and a deployed file still carrying keys that no
 * longer exist - which has to cost a WARN and a {@code .bak}, never a refusal to start.
 * </p>
 */
class ConfigsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigsTest.class);

    @TempDir
    Path directory;

    @Test
    @DisplayName("a fresh file backs up the four volumes that cannot be rebuilt, and never PGDATA")
    void whatANightlyBackupSaves() throws Exception {
        final StewardSpec config = Configs.steward(directory, LOGGER).get();
        final java.util.List<String> volumes = config.backup().volumes();

        // Nordtal is a hand-built world in no repository and in no release; the plugins volumes
        // hold every hand-edited config in the deployment, which is exactly what a deployment that
        // checks the project out over itself used to be able to delete (finding 151).
        assertTrue(volumes.contains("nordtal-s2_mc-smp"), volumes.toString());
        assertTrue(volumes.contains("nordtal-s2_mc-smp-plugins"), volumes.toString());
        assertTrue(volumes.stream().noneMatch(volume -> volume.endsWith("postgres-data")),
                "a snapshot of a live PGDATA fails at RESTORE and nowhere else: " + volumes);

        // The database is DUMPED rather than snapshotted, straight into backup.output-root, so it
        // needs no volume here at all - and the postgres-dumps volume that used to be in this list
        // went with the sidecar that wrote it (§9a). Nor is the output directory itself ever here:
        // a backup of the backups doubles every night until the disk is gone.
        assertTrue(volumes.stream().noneMatch(volume -> volume.contains("dumps")
                        || volume.contains("backups")),
                "the backups are not a thing to back up: " + volumes);

        // The stop list and the volume list are not the same list, deliberately: limbo and
        // hunger-games hold no world worth saving, so stopping them would be an outage with
        // nothing to show for it, while their plugins/ volumes are still worth a snapshot.
        assertEquals(java.util.List.of(eu.nordtal.s2.steward.worker.plan.Topology.SMP,
                        eu.nordtal.s2.steward.worker.plan.Topology.PROXY,
                        eu.nordtal.s2.steward.worker.plan.Topology.DISCORD_BOT),
                config.backup().stopServices());

        // Thirty rather than sixty (owner, 2026-09-09), and the two halves of that decision are
        // one decision: the wait was shortened because giving up stopped being silent. A run that
        // ends FAILED mentions the admin role through UpdateFeed, so the network coming back after
        // half an hour with one volume unsaved is something a person is told about rather than
        // something they find. Raising this back without that mention would put the network's
        // longest unattended outage behind an embed nobody reads at five in the morning.
        assertEquals(30, config.backup().patienceMinutes());
    }

    @Test
    @DisplayName("listing postgres-data is refused by name, not warned about")
    void theDataDirectoryIsRefused() throws Exception {
        java.nio.file.Files.writeString(directory.resolve("steward.yml"), """
                backup:
                  volumes:
                    - 'nordtal-s2_postgres-data'
                """);

        final ConfigValidationException error =
                assertThrows(ConfigValidationException.class, () -> Configs.steward(directory, LOGGER));

        final String message = String.valueOf(error.getMessage() + error.getCause());
        assertTrue(message.contains("postgres-dumps"),
                "and it names the volume that should have been there instead: " + message);
    }

    @Test
    @DisplayName("a volume list without the world is refused, because the world cannot be rebuilt")
    void theWorldCannotBeDroppedFromTheList() throws Exception {
        // The hole this closes: a run reports DONE for saving what this list names, and the list
        // is the only thing that says what that was. Keep one volume, drop mc-smp, and every night
        // reports DONE while Nordtal - the one thing in no repository and no release - is in no
        // archive at all.
        java.nio.file.Files.writeString(directory.resolve("steward.yml"), """
                backup:
                  volumes:
                    - 'nordtal-s2_bot-config'
                """);

        final ConfigValidationException error =
                assertThrows(ConfigValidationException.class, () -> Configs.steward(directory, LOGGER));

        final String message = String.valueOf(error.getMessage() + error.getCause());
        assertTrue(message.contains("in no repository and in no"),
                "and it says what the missing volume is load-bearing for: " + message);
    }

    @Test
    @DisplayName("a deployed steward.yml still carrying retired keys loses them and starts")
    void theRetiredKeysAreDroppedRatherThanFatal() throws Exception {
        // The four keys that were retired on 2026-09-09: two versions that became constants in
        // :common and two build pins that became nothing at all. Every deployed volume in existence
        // carries all four, and the only move an operator has when a load refuses is to delete four
        // lines that mean nothing any more - which is why jcore 3.1.0 answers a RETIRED key
        // differently from a MISSPELLED one: the line goes, with a WARN and a .bak, and the process
        // starts. Named here rather than merely tolerated, because the half that is the actual
        // point is that nobody re-declares one as a quiet no-op to make an upgrade smoother.
        //
        // The same now goes for the whole `arcane:` block, retired on 2026-09-13 with the panel it
        // configured. Every steward.yml in every deployed volume has one, and a worker that refused
        // to start over it would take the four servers waiting on it down with it.
        Configs.steward(directory, LOGGER);

        final Path file = directory.resolve("steward.yml");
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
                + """

                minecraft-version: '26.2'
                velocity-version: '4.1.1'
                paper-build: latest
                velocity-build: '24'
                arcane:
                  base-url: 'https://arcane.example.com'
                  api-key: 'token'
                """, StandardCharsets.UTF_8);

        final StewardSpec config = Configs.steward(directory, LOGGER).get();
        assertEquals("nordtal/season-2", config.seasonRepo(), "steward-worker refused to start");

        final String written = Files.readString(file, StandardCharsets.UTF_8);
        for (final String retired : new String[] {
                "minecraft-version", "velocity-version", "paper-build", "velocity-build",
                "arcane"}) {
            assertFalse(written.contains(retired),
                    "steward.yml still carries '" + retired + "' after a load. Either it was"
                            + " re-declared - which makes an operator believe a value nothing"
                            + " reads - or jcore stopped trimming retired keys.");
        }
        assertTrue(Files.isRegularFile(directory.resolve("steward.yml.bak")),
                "the old content is not in a .bak, so an operator who wanted those lines back has"
                        + " nowhere to read them from");
    }

    // ---------------------------------------------------------------- bunq (steward/109)

    @Test
    @DisplayName("a fresh file has no bunq credentials, and that is a valid deployment")
    void aFreshFileHasNoBankAccount() throws Exception {
        final StewardSpec.BunqSpec bunq = Configs.steward(directory, LOGGER).get().bunq();

        // Empty is the default and the load succeeded, which is the whole assertion: a season
        // without a bank account is a season where everything works except buying access, and it
        // must be able to start. The account is the one thing in this file that cannot be created
        // from a terminal.
        assertEquals("", bunq.apiKey());
        assertEquals("", bunq.accountId());
        assertEquals(30, bunq.pollIntervalSeconds());
        assertEquals(50, bunq.recentPaymentCount());
        assertEquals("", bunq.watermark(), "the first start stamps its own instant; see Watermark");
    }

    @Test
    @DisplayName("half a bunq credential is refused, and the message names the old variables")
    void halfABunqCredentialIsRefused() throws Exception {
        // This is the failure the rename actually produces: somebody sets NORDTAL_STEWARD_BUNQ_API_KEY
        // in the environment file and leaves NORDTAL_BOT_BUNQ_ACCOUNT_ID where it was. Refusing to
        // start is right - a key with no account is always a setup that stopped in the middle - and
        // the message has to say what happened, because the variable that IS set looks correct.
        Files.writeString(directory.resolve("steward.yml"), """
                bunq:
                  api-key: 'a-key'
                  account-id: ''
                """);

        final ConfigValidationException error =
                assertThrows(ConfigValidationException.class, () -> Configs.steward(directory, LOGGER));

        final String message = String.valueOf(error.getMessage()) + error.getCause();
        assertTrue(message.contains("both api-key and account-id or neither"), message);
        assertTrue(message.contains("NORDTAL_BOT_BUNQ_"),
                "the message has to name the names this deployment probably still uses: " + message);
    }

    @Test
    @DisplayName("a non-numeric bunq account id is caught at startup, not inside a poll")
    void aNonNumericAccountIdIsRefused() throws Exception {
        Files.writeString(directory.resolve("steward.yml"), """
                bunq:
                  api-key: 'a-key'
                  account-id: 'NL91BUNQ0417164300'
                """);

        final ConfigValidationException error =
                assertThrows(ConfigValidationException.class, () -> Configs.steward(directory, LOGGER));

        // An IBAN is the wrong answer somebody will actually give, and BunqGateway parses the id
        // with Long.parseLong in its constructor - so without this the failure is a
        // NumberFormatException at startup with no sentence attached to it.
        final String message = String.valueOf(error.getMessage()) + error.getCause();
        assertTrue(message.contains("must be a number"), message);
    }

    @Test
    @DisplayName("a watermark override that is not an instant is refused")
    void anUnreadableWatermarkIsRefused() throws Exception {
        // Moved here from discord-bot's ConfigsTest with the setting itself. An unreadable
        // watermark would otherwise surface as a poll that books nothing, which is indistinguishable
        // from a quiet bank.
        Files.writeString(directory.resolve("steward.yml"), """
                bunq:
                  watermark: '1 September 2026'
                """);

        final ConfigValidationException error =
                assertThrows(ConfigValidationException.class, () -> Configs.steward(directory, LOGGER));

        final String message = String.valueOf(error.getMessage()) + error.getCause();
        assertTrue(message.contains("ISO-8601"), message);
    }

    @Test
    @DisplayName("a complete bunq block loads, and the id keeps its own text")
    void aCompleteBunqBlockLoads() throws Exception {
        Files.writeString(directory.resolve("steward.yml"), """
                bunq:
                  api-key: 'a-key'
                  account-id: '987654'
                  poll-interval-seconds: 45
                  recent-payment-count: 10
                  watermark: '2026-09-01T00:00:00Z'
                """);

        final StewardSpec.BunqSpec bunq = Configs.steward(directory, LOGGER).get().bunq();
        assertEquals("987654", bunq.accountId());
        assertEquals(45, bunq.pollIntervalSeconds());
        assertEquals(10, bunq.recentPaymentCount());
        assertEquals("2026-09-01T00:00:00Z", bunq.watermark());
    }
}
