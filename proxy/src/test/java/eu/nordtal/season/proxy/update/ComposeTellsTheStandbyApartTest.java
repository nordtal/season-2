package eu.nordtal.season.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.ComposeFile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The one value in {@code compose.yml} that tells the two proxies apart.
 *
 * Nothing compiles that file, and a missing or misplaced value fails silently in both directions.
 */
class ComposeTellsTheStandbyApartTest {

    private static final String KEY = "NORDTAL_PROXY_NETWORK_STANDBY";

    @Test
    void composeNamesTheStandby() {
        final Map<String, String> live = ComposeFile.get().service("proxy").environment();
        final Map<String, String> standby =
                ComposeFile.get().service("proxy-standby").environment();

        assertEquals(
                "false",
                live.get(KEY),
                KEY + " under `proxy` has to be false: that process is the one players connect to,"
                        + " and a live proxy that thinks it is the standby transfers everybody to"
                        + " the address they are already on");
        assertEquals(
                "true",
                standby.get(KEY),
                KEY + " under `proxy-standby` has to be true, and it is the ONLY thing that makes"
                        + " that container a standby: without it the container starts, looks"
                        + " healthy, releases parked players onto the backends and never sends"
                        + " anybody home");

        assertTrue(
                standby.keySet().containsAll(live.keySet()),
                "the standby has to MERGE the shared environment rather than replace it, or every"
                        + " setting the live proxy gains from now on reaches only one of them");
    }
}
