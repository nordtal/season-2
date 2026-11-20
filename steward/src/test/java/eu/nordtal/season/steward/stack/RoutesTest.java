package eu.nordtal.season.steward.stack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoutesTest {

    @Test
    void settingsThatControlTheStackRequireAFreshKey() {
        assertTrue(Routes.needsFreshKeyForSettings("steward", "steward"));
        assertTrue(Routes.needsFreshKeyForSettings("steward", "web"));
        assertTrue(Routes.needsFreshKeyForSettings("steward-agent", "runs"));
        assertTrue(Routes.needsFreshKeyForSettings("proxy", "pack"));
        assertFalse(Routes.needsFreshKeyForSettings("steward", "alerts"));
        assertFalse(Routes.needsFreshKeyForSettings("network", "language-and-time"));
        assertFalse(Routes.needsFreshKeyForSettings("steward/web", ""));
    }
}
