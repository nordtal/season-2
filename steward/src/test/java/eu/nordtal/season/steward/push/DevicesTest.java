package eu.nordtal.season.steward.push;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Tests {@link Devices#of} with real User-Agents. */
class DevicesTest {

    private static final Devices.Device NOTHING = new Devices.Device(null, null);

    @Test
    void iphoneSafari() {
        assertEquals(
                new Devices.Device("iPhone", "Safari"),
                Devices.of("Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15"
                        + " (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1"));
    }

    @Test
    void chromeIsNotSafari() {
        assertEquals(
                new Devices.Device("Linux", "Chrome"),
                Devices.of("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko)"
                        + " Chrome/140.0.0.0 Safari/537.36"));
    }

    @Test
    void edgeIsNotChrome() {
        assertEquals(
                new Devices.Device("Windows", "Edge"),
                Devices.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                        + " Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0"));
    }

    @Test
    void firefoxOnAMac() {
        assertEquals(
                new Devices.Device("Mac", "Firefox"),
                Devices.of(
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:143.0) Gecko/20100101" + " Firefox/143.0"));
    }

    @Test
    void nothingToSayIsNull() {
        assertEquals(NOTHING, Devices.of(null));
        assertEquals(NOTHING, Devices.of(""));
        assertEquals(NOTHING, Devices.of("curl/8.5.0"));
        assertEquals(
                NOTHING,
                Devices.of("x".repeat(1000)),
                "a User-Agent nobody could have sent was read as if it were one");
    }
}
