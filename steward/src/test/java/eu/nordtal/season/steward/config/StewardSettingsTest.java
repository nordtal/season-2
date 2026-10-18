package eu.nordtal.season.steward.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.settings.MemorySettingStore;
import eu.nordtal.season.settings.Setting;
import eu.nordtal.season.settings.SettingsException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests what the steward group refuses and what it starts with. A refused change keeps the values in use. */
class StewardSettingsTest {

    private final MemorySettingStore store = new MemorySettingStore();

    private Setting<StewardSpec> steward() throws SettingsException {
        return StewardSettings.steward(store.settings(StewardSettings.SERVICE));
    }

    /** Stores {@code value} at {@code path} as an admin does and loads the group again. */
    private SettingsException refused(final String path, final Object value) throws SettingsException {
        final Setting<StewardSpec> steward = steward();
        store.set(StewardSettings.SERVICE, "steward", path, value);
        return assertThrows(SettingsException.class, steward::reload);
    }

    @Test
    void noBankTokenIsAValidDeployment() throws Exception {
        final StewardSpec.BunqSpec bunq = steward().get().bunq();

        // An empty value is the default and loads, so a season without a bank account starts.
        assertEquals("", bunq.token());
        assertEquals("http://steward-bunq:8082", bunq.url());
        assertEquals(30, bunq.pollIntervalSeconds());
        assertEquals(50, bunq.recentPaymentCount());
        assertEquals("", bunq.watermark(), "the first start stamps its own instant; see Watermark");
    }

    @Test
    void aWatermarkThatIsNotAnInstantIsRefused() throws Exception {
        // An unreadable watermark is refused, not left as a poll that books nothing.
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
