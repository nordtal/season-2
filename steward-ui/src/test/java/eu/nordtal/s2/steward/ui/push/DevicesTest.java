package eu.nordtal.s2.steward.ui.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Devices#nameOf} against the User-Agents that actually reach this service.
 *
 * <p>The strings below are real ones, copied rather than invented - the three that matter are an
 * iPhone (the device steward/06's acceptance is about), a desktop Chrome and a desktop Firefox. The
 * point of each assertion is the trap it walks past: every one of these carries the word "Mozilla",
 * Chrome's carries "Safari", and Edge's carries both "Chrome" and "Safari".</p>
 */
class DevicesTest {

    @Test
    @DisplayName("an iPhone is an iPhone, and Safari on it is Safari")
    void iphoneSafari() {
        assertEquals(
                "iPhone, Safari",
                Devices.nameOf("Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15"
                        + " (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1"));
    }

    @Test
    @DisplayName("Chrome is not Safari, however much its own User-Agent says so")
    void chromeIsNotSafari() {
        assertEquals(
                "Linux, Chrome",
                Devices.nameOf("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko)"
                        + " Chrome/140.0.0.0 Safari/537.36"));
    }

    @Test
    @DisplayName("Edge is not Chrome, however much its own User-Agent says so")
    void edgeIsNotChrome() {
        assertEquals(
                "Windows, Edge",
                Devices.nameOf("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                        + " Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0"));
    }

    @Test
    @DisplayName("Firefox on a Mac")
    void firefoxOnAMac() {
        assertEquals(
                "Mac, Firefox",
                Devices.nameOf(
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:143.0) Gecko/20100101" + " Firefox/143.0"));
    }

    @Test
    @DisplayName("nothing to say is null, never the word for it")
    void nothingToSayIsNull() {
        assertNull(Devices.nameOf(null));
        assertNull(Devices.nameOf(""));
        assertNull(Devices.nameOf("curl/8.5.0"));
        assertNull(Devices.nameOf("x".repeat(1000)), "a User-Agent nobody could have sent was read as if it were one");
    }
}
