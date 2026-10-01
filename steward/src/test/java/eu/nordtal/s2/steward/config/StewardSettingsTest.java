package eu.nordtal.s2.steward.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.settings.MemorySettingStore;
import eu.nordtal.s2.settings.Setting;
import eu.nordtal.s2.settings.SettingsException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the steward group refuses, and what it starts with.
 *
 * A live PGDATA in {@code backup.volumes} is refused; a refused change keeps the values in use.
 */
class StewardSettingsTest {

    private final MemorySettingStore store = new MemorySettingStore();

    private Setting<StewardSpec> steward() throws SettingsException {
        return StewardSettings.steward(store.settings(StewardSettings.SERVICE));
    }

    /** Stores {@code value} at {@code path} as an admin would and takes the group again. */
    private SettingsException refused(final String path, final Object value) throws SettingsException {
        final Setting<StewardSpec> steward = steward();
        store.set(StewardSettings.SERVICE, "steward", path, value);
        return assertThrows(SettingsException.class, steward::reload);
    }

    @Test
    void theDefaultsBackUpTheFourVolumesThatCannotBeRebuiltAndNeverPgdata() throws Exception {
        final StewardSpec config = steward().get();
        final List<String> volumes = config.backup().volumes();

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
                List.of(eu.nordtal.s2.steward.plan.Topology.SMP, eu.nordtal.s2.steward.plan.Topology.DISCORD_BOT),
                config.backup().stopServices());
        assertFalse(
                volumes.stream().anyMatch(volume -> volume.contains("proxy") || volume.contains("limbo")),
                "proxy and limbo left the backup and a restart writes everything they hold: " + volumes);

        // A run ending FAILED mentions the admin role through UpdateFeed, so a half-hour outage is not silent.
        assertEquals(30, config.backup().patienceMinutes());
    }

    @Test
    void listingPostgresDataIsRefusedByNameNotWarnedAbout() throws Exception {
        final SettingsException error = refused("backup.volumes", "[\"nordtal-s2_postgres-data\"]");

        final String message = String.valueOf(error.getMessage() + error.getCause());
        assertTrue(
                message.contains("postgres-dumps"),
                "and it names the volume that should have been there instead: " + message);
    }

    @Test
    void aVolumeListWithoutTheWorldIsRefusedBecauseTheWorldCannotBeRebuilt() throws Exception {
        // A run reports DONE for saving what this list names; dropping mc-smp would still report DONE without it.
        final SettingsException error = refused("backup.volumes", "[\"nordtal-s2_bot-config\"]");

        final String message = String.valueOf(error.getMessage() + error.getCause());
        assertTrue(
                message.contains("in no repository and in no"),
                "and it says what the missing volume is load-bearing for: " + message);
    }

    @Test
    void aRefusedChangeKeepsTheValuesInUseAndSaysWhyOnTheGroup() throws Exception {
        final Setting<StewardSpec> steward = steward();
        store.set(StewardSettings.SERVICE, "steward", "backup.volumes", "[\"nordtal-s2_postgres-data\"]");

        assertThrows(SettingsException.class, steward::reload);

        assertTrue(steward.get().backup().volumes().contains("nordtal-s2_mc-smp"));
        assertTrue(store.group(StewardSettings.SERVICE, "steward").orElseThrow().problem() != null);
    }

    @Test
    void noBankTokenIsAValidDeployment() throws Exception {
        final StewardSpec.BunqSpec bunq = steward().get().bunq();

        // Empty is the default and the load succeeds: a season with no bank account must still be able to start.
        assertEquals("", bunq.token());
        assertEquals("http://steward-bunq:8082", bunq.url());
        assertEquals(30, bunq.pollIntervalSeconds());
        assertEquals(50, bunq.recentPaymentCount());
        assertEquals("", bunq.watermark(), "the first start stamps its own instant; see Watermark");
    }

    @Test
    void aWatermarkThatIsNotAnInstantIsRefused() throws Exception {
        // An unreadable watermark must not surface as a poll that books nothing, indistinguishable from a quiet bank.
        final SettingsException error = refused("bunq.watermark", "1 September 2026");

        final String message = String.valueOf(error.getMessage()) + error.getCause();
        assertTrue(message.contains("ISO-8601"), message);
    }

    @Test
    void aCompleteBunqBlockLoadsWithItsTokenFromTheEnvironment() throws Exception {
        store.set(StewardSettings.SERVICE, "steward", "bunq.poll-interval-seconds", 45)
                .set(StewardSettings.SERVICE, "steward", "bunq.recent-payment-count", 10)
                .set(StewardSettings.SERVICE, "steward", "bunq.watermark", "2026-09-01T00:00:00Z");

        final StewardSpec.BunqSpec bunq = StewardSettings.steward(store.settings(
                        StewardSettings.SERVICE,
                        StewardSettings.ENVIRONMENT.reading(Map.of("NORDTAL_STEWARD_BUNQ_TOKEN", "a-token")::get)))
                .get()
                .bunq();

        assertEquals("a-token", bunq.token());
        assertEquals(45, bunq.pollIntervalSeconds());
        assertEquals(10, bunq.recentPaymentCount());
        assertEquals("2026-09-01T00:00:00Z", bunq.watermark());
    }
}
