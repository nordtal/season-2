package eu.nordtal.season.proxy.update;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * The two addresses a proxy swap moves players between.
 *
 * Always unresolved, so the client gets the configured name and Velocity never does a reverse lookup.
 */
public final class SwapAddresses {

    private SwapAddresses() {}

    /**
     * Parses {@code network#public-address}.
     *
     * @param address {@code host:port}, or {@code [::1]:port}, or blank
     * @return the address, or empty when it is blank or has no usable port, meaning this deployment does not swap
     */
    public static Optional<InetSocketAddress> publicAddress(final String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        final String trimmed = address.trim();

        final String host;
        final String port;
        if (trimmed.startsWith("[")) {
            // A bare IPv6 literal has colons of its own; only the brackets say where the host ends.
            final int close = trimmed.indexOf(']');
            if (close < 0 || close + 2 >= trimmed.length() || trimmed.charAt(close + 1) != ':') {
                return Optional.empty();
            }
            host = trimmed.substring(1, close);
            port = trimmed.substring(close + 2);
        } else {
            final int colon = trimmed.lastIndexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                return Optional.empty();
            }
            host = trimmed.substring(0, colon);
            port = trimmed.substring(colon + 1);
        }

        if (host.isBlank()) {
            return Optional.empty();
        }
        return port(port).map(number -> InetSocketAddress.createUnresolved(host, number));
    }

    /**
     * The standby's address: the same host, the other port.
     *
     * @param address the parsed {@link #publicAddress}
     * @param standbyPort {@code network#standby-port}
     * @return where to send a player during the swap, or empty when the port is invalid or the live proxy's own
     */
    public static Optional<InetSocketAddress> standbyAddress(final InetSocketAddress address, final int standbyPort) {
        if (address == null) {
            return Optional.empty();
        }
        if (standbyPort == address.getPort()) {
            return Optional.empty();
        }
        return port(String.valueOf(standbyPort))
                .map(number -> InetSocketAddress.createUnresolved(address.getHostString(), number));
    }

    private static Optional<Integer> port(final String text) {
        try {
            final int number = Integer.parseInt(text.trim());
            return number >= 1 && number <= 65535 ? Optional.of(number) : Optional.empty();
        } catch (final NumberFormatException notANumber) {
            return Optional.empty();
        }
    }
}
