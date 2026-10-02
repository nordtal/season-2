package eu.nordtal.s2.steward.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.settings.MemorySettingStore;
import eu.nordtal.s2.settings.Setting;
import eu.nordtal.s2.settings.SettingsException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the steward group refuses, and what it starts with.
 *
 * A refused change keeps the values in use.
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
    void aBackupWaitsHalfAnHourForTheServersByDefault() throws Exception {
        // A run ending FAILED mentions the admin role through UpdateFeed, so a half-hour outage is not silent.
        assertEquals(30, steward().get().backup().patienceMinutes());
    }

    @Test
    void aRefusedChangeKeepsTheValuesInUseAndSaysWhyOnTheGroup() throws Exception {
        final Setting<StewardSpec> steward = steward();
        store.set(StewardSettings.SERVICE, "steward", "backup.patience-minutes", 0);

        assertThrows(SettingsException.class, steward::reload);

        assertEquals(30, steward.get().backup().patienceMinutes());
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
