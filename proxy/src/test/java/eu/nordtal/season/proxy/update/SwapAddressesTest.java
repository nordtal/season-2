package eu.nordtal.season.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The two addresses a swap moves players between for a proxy that has been swapped in. */
class SwapAddressesTest {

    @Test
    void theAddressStaysAName() {
        // Unresolved: the transfer packet uses getHostName(), a blocking reverse lookup when resolved.
        final InetSocketAddress address =
                SwapAddresses.publicAddress("play.nordtal.eu:25565").orElseThrow();
        assertTrue(address.isUnresolved());
        assertEquals("play.nordtal.eu", address.getHostName());
        assertEquals(25565, address.getPort());
    }

    @Test
    void thePortIsNotOptional() {
        // A SRV record hides the port and nothing looks one up for a transfer, so 25565 would be a guess.
        assertEquals(Optional.empty(), SwapAddresses.publicAddress("play.nordtal.eu"));
        assertEquals(Optional.empty(), SwapAddresses.publicAddress("play.nordtal.eu:"));
        assertEquals(Optional.empty(), SwapAddresses.publicAddress("play.nordtal.eu:no"));
        assertEquals(Optional.empty(), SwapAddresses.publicAddress("play.nordtal.eu:0"));
        assertEquals(Optional.empty(), SwapAddresses.publicAddress("play.nordtal.eu:70000"));
    }

    @Test
    void emptyIsAValidAnswer() {
        assertEquals(Optional.empty(), SwapAddresses.publicAddress(""));
        assertEquals(Optional.empty(), SwapAddresses.publicAddress("   "));
        assertEquals(Optional.empty(), SwapAddresses.publicAddress(null));
    }

    @Test
    void ipv6KeepsItsColons() {
        final InetSocketAddress address =
                SwapAddresses.publicAddress("[2001:db8::1]:25565").orElseThrow();
        assertEquals("2001:db8::1", address.getHostString());
        assertEquals(25565, address.getPort());
    }

    @Test
    void theStandbyIsTheSameHost() {
        final InetSocketAddress home =
                SwapAddresses.publicAddress("play.nordtal.eu:25565").orElseThrow();
        final InetSocketAddress standby =
                SwapAddresses.standbyAddress(home, 25566).orElseThrow();
        assertEquals("play.nordtal.eu", standby.getHostString());
        assertEquals(25566, standby.getPort());
        assertTrue(standby.isUnresolved());
    }

    @Test
    void theSamePortIsNoStandby() {
        // PROXY_STANDBY_PORT and PROXY_PORT are two variables; equal ones would move nobody.
        final InetSocketAddress home =
                SwapAddresses.publicAddress("play.nordtal.eu:25565").orElseThrow();
        assertFalse(SwapAddresses.standbyAddress(home, 25565).isPresent());
        assertFalse(SwapAddresses.standbyAddress(home, 0).isPresent());
        assertFalse(SwapAddresses.standbyAddress(null, 25566).isPresent());
    }
}
