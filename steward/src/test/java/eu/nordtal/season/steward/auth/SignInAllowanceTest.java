package eu.nordtal.season.steward.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SignInAllowanceTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-23T12:00:00Z"));

    private final SignInAllowance allowance = new SignInAllowance(now::get, "the client secret");

    @Test
    void aFloodFromManyAddressesLeavesAKnownBrowserItsOwnAllowance() {
        final String known = allowance.knownCookie("42");
        int admitted = 0;
        for (int address = 1; address <= 100; address++) {
            if (allowance.admit(null, "203.0.113." + address).isEmpty()) {
                admitted++;
            }
        }
        assertEquals(30, admitted, "the strangers' overall cap must hold");
        assertTrue(allowance.admit(null, "198.51.100.7").isPresent(), "a first sign-in waits out the flood");

        assertEquals(Optional.empty(), allowance.admit(known, "198.51.100.7"));
    }

    @Test
    void theKnownAllowanceHasACapOfItsOwn() {
        int admitted = 0;
        for (int id = 1; id <= 20; id++) {
            if (allowance
                    .admit(allowance.knownCookie(String.valueOf(id)), "203.0.113.1")
                    .isEmpty()) {
                admitted++;
            }
        }
        assertEquals(10, admitted);
    }

    @Test
    void aForgedOrExpiredCookieCountsAsAStranger() {
        final String known = allowance.knownCookie("42");
        final String forged = known.replaceFirst("^42\\.", "43.");
        final String otherKey = new SignInAllowance(now::get, "another secret").knownCookie("42");

        assertEquals(Optional.of("42"), allowance.knownId(known));
        assertEquals(Optional.empty(), allowance.knownId(forged));
        assertEquals(Optional.empty(), allowance.knownId(otherKey));
        assertEquals(Optional.empty(), allowance.knownId("not a cookie"));
        now.set(now.get().plus(SignInAllowance.KNOWN_FOR).plusSeconds(1));
        assertEquals(Optional.empty(), allowance.knownId(known));
    }

    @Test
    void addressesInOneIpv6Slash64ShareOneAllowance() {
        for (int host = 1; host <= 5; host++) {
            assertEquals(Optional.empty(), allowance.admit(null, "2001:db8:1:2::" + Integer.toHexString(host)));
        }
        assertTrue(allowance.admit(null, "2001:db8:1:2:ffff:ffff:ffff:ffff").isPresent());
        assertEquals(Optional.empty(), allowance.admit(null, "2001:db8:1:3::1"));
    }

    @Test
    void anIpv6AddressIsKeyedByItsSlash64AndAnythingElseAsItIs() {
        assertEquals(
                SignInAllowance.addressKey("2001:db8:1:2::1"),
                SignInAllowance.addressKey("2001:0db8:0001:0002:aaaa::"));
        assertEquals("203.0.113.1", SignInAllowance.addressKey("203.0.113.1"));
        assertEquals("not an address", SignInAllowance.addressKey("not an address"));
    }

    @Test
    void aRefusedAddressLearnsHowLongToWait() {
        for (int attempt = 0; attempt < 5; attempt++) {
            allowance.admit(null, "203.0.113.1");
        }
        now.set(now.get().plusSeconds(15));
        assertEquals(Optional.of(Duration.ofSeconds(45)), allowance.admit(null, "203.0.113.1"));
    }
}
