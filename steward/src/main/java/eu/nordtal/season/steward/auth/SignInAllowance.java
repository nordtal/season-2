package eu.nordtal.season.steward.auth;

import eu.nordtal.season.steward.RequestLimit;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Decides whether a sign-in may exchange its code with Discord.
 *
 * A stranger counts against its address and an overall cap. A browser that signed in before has a cap of its own.
 */
final class SignInAllowance {

    static final String COOKIE = "steward_known";

    static final Duration KNOWN_FOR = Duration.ofDays(365);

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int PER_ADDRESS = 5;
    private static final int STRANGERS = 30;
    private static final int PER_KNOWN = 5;
    private static final int KNOWN = 10;
    private static final String MAC = "HmacSHA256";

    private final InstantSource clock;
    private final SecretKeySpec key;
    private final RequestLimit<String> strangers;
    private final RequestLimit<String> known;

    /** Derives the cookie key from {@code secret}, so a new client secret forgets every known browser. */
    SignInAllowance(final InstantSource clock, final String secret) {
        this.clock = clock;
        this.key = new SecretKeySpec(
                mac(new SecretKeySpec("steward known browser".getBytes(StandardCharsets.UTF_8), MAC), secret), MAC);
        this.strangers = new RequestLimit<>(clock, WINDOW, PER_ADDRESS, STRANGERS);
        this.known = new RequestLimit<>(clock, WINDOW, PER_KNOWN, KNOWN);
    }

    /** Returns empty when the exchange may run, or how long this browser waits. */
    Optional<Duration> admit(final @Nullable String cookie, final String address) {
        return knownId(cookie).map(known::acquireOrWait).orElseGet(() -> strangers.acquireOrWait(addressKey(address)));
    }

    /** Returns the cookie value that marks a browser as signed in once by {@code discordId}. */
    String knownCookie(final String discordId) {
        final String claim = discordId + "." + clock.instant().plus(KNOWN_FOR).getEpochSecond();
        return claim + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(key, claim));
    }

    /** Returns the Discord id a valid, unexpired cookie names. */
    Optional<String> knownId(final @Nullable String cookie) {
        if (cookie == null) {
            return Optional.empty();
        }
        final int signature = cookie.lastIndexOf('.');
        final int expiry = signature < 0 ? -1 : cookie.lastIndexOf('.', signature - 1);
        if (expiry <= 0) {
            return Optional.empty();
        }
        final String claim = cookie.substring(0, signature);
        final byte[] given;
        final long expires;
        try {
            given = Base64.getUrlDecoder().decode(cookie.substring(signature + 1));
            expires = Long.parseLong(cookie.substring(expiry + 1, signature));
        } catch (final IllegalArgumentException malformed) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(mac(key, claim), given)
                || !clock.instant().isBefore(Instant.ofEpochSecond(expires))) {
            return Optional.empty();
        }
        return Optional.of(cookie.substring(0, expiry));
    }

    /** Keys an IPv6 address by its /64 prefix, since one subscriber usually holds the whole prefix. */
    static String addressKey(final String address) {
        // Only a literal with a colon is parsed, so a host name in the header never triggers a DNS lookup.
        if (!address.contains(":")) {
            return address;
        }
        try {
            final InetAddress parsed = InetAddress.getByName(address);
            if (parsed instanceof Inet6Address six) {
                return HexFormat.of().formatHex(Arrays.copyOf(six.getAddress(), 8)) + "::/64";
            }
            // An IPv4-mapped IPv6 literal parses to its IPv4 address.
            return parsed.getHostAddress();
        } catch (final UnknownHostException notAnAddress) {
            return address;
        }
    }

    private static byte[] mac(final SecretKeySpec key, final String text) {
        try {
            final Mac mac = Mac.getInstance(MAC);
            mac.init(key);
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is part of every JDK", e);
        }
    }
}
