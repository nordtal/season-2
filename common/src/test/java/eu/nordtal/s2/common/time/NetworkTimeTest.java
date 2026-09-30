package eu.nordtal.s2.common.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.RepositoryRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class NetworkTimeTest {

    @Test
    void theProcessClockRunsInTheNetworkZone() {
        assertEquals(NetworkTime.ZONE, NetworkTime.clock().getZone());
    }

    @Test
    void theContainersRunInTheNetworkZone() throws IOException {
        final String compose = Files.readString(RepositoryRoot.path().resolve("compose.yml"));
        final Matcher zones = Pattern.compile("TZ: \\$\\{TZ:-([^}]+)}").matcher(compose);
        int found = 0;
        while (zones.find()) {
            assertEquals(NetworkTime.ZONE.getId(), zones.group(1), "a container's default TZ left the network zone");
            found++;
        }
        assertTrue(found > 0, "compose.yml sets no TZ at all");
    }
}
