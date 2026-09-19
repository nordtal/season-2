package eu.nordtal.s2.proxy.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two addresses a swap moves players between (season-2-ops/121). */
class SwapAddressesTest {

    @Test
    @DisplayName("the address a player is handed is never resolved on this side")
    void theAddressStaysAName() {
        // The whole reason SwapAddresses exists. Velocity builds the transfer packet from
        // getHostName(), and on a RESOLVED address that is a reverse DNS lookup on whichever
        // thread holds it - so a resolved address here would be both a blocking call and,
        // potentially, a different name than the one Till configured.
        final InetSocketAddress address = SwapAddresses.publicAddress("play.nordtal.eu:25565")
                .orElseThrow();
        assertTrue(address.isUnresolved());
        assertEquals("play.nordtal.eu", address.getHostName());
        assertEquals(25565, address.getPort());
    }

    @Test
    @DisplayName("no port is no address, because a transfer packet has nowhere to get one")
    void thePortIsNotOptional() {
        // A SRV record hides the port from somebody typing a name into a client. Nothing looks one
        // up on behalf of a transfer, so guessing 25565 here would transfer everybody to a port
        // that may have nothing on it.
        assertEquals(Optional.empty(), SwapAddresses.publicAddress("play.nordtal.eu"));
        assertEquals(Optional.empty(), SwapAddresses.publicAddress("play.nordtal.eu:"));
        assertEquals(Optional.empty(), SwapAddresses.publicAddress("play.nordtal.eu:no"));
        assertEquals(Optional.empty(), SwapAddresses.publicAddress("play.nordtal.eu:0"));
        assertEquals(Optional.empty(), SwapAddresses.publicAddress("play.nordtal.eu:70000"));
    }

    @Test
    @DisplayName("empty is a valid answer and means this deployment does not swap proxies")
    void emptyIsAValidAnswer() {
        assertEquals(Optional.empty(), SwapAddresses.publicAddress(""));
        assertEquals(Optional.empty(), SwapAddresses.publicAddress("   "));
        assertEquals(Optional.empty(), SwapAddresses.publicAddress(null));
    }

    @Test
    @DisplayName("an IPv6 literal keeps its own colons")
    void ipv6KeepsItsColons() {
        final InetSocketAddress address = SwapAddresses.publicAddress("[2001:db8::1]:25565")
                .orElseThrow();
        assertEquals("2001:db8::1", address.getHostString());
        assertEquals(25565, address.getPort());
    }

    @Test
    @DisplayName("the standby is the same host on the other port")
    void theStandbyIsTheSameHost() {
        final InetSocketAddress home = SwapAddresses.publicAddress("play.nordtal.eu:25565")
                .orElseThrow();
        final InetSocketAddress standby = SwapAddresses.standbyAddress(home, 25566).orElseThrow();
        assertEquals("play.nordtal.eu", standby.getHostString());
        assertEquals(25566, standby.getPort());
        assertTrue(standby.isUnresolved());
    }

    @Test
    @DisplayName("a standby on the live port is refused, because that is a transfer to nowhere")
    void theSamePortIsNoStandby() {
        // Not hypothetical: PROXY_STANDBY_PORT and PROXY_PORT are two variables in one .env, and
        // setting them equal is one keystroke. The result would be every player transferred to the
        // address they are already connected to, seconds before that proxy stops.
        final InetSocketAddress home = SwapAddresses.publicAddress("play.nordtal.eu:25565")
                .orElseThrow();
        assertFalse(SwapAddresses.standbyAddress(home, 25565).isPresent());
        assertFalse(SwapAddresses.standbyAddress(home, 0).isPresent());
        assertFalse(SwapAddresses.standbyAddress(null, 25566).isPresent());
    }
}
