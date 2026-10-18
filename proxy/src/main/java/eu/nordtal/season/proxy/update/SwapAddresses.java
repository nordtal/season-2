package eu.nordtal.season.proxy.update;

import java.net.InetSocketAddress;
import java.util.Optional;

/** Parses the addresses of the proxy swap. The addresses are unresolved. */
public final class SwapAddresses {

    private SwapAddresses() {}

    /**
     * Parses {@code network#public-address} or {@code network#live-address}.
     *
     * @param address {@code host:port}, {@code [::1]:port} or blank
     * @return the address, or empty when the address is blank or has no valid port
     */
    public static Optional<InetSocketAddress> publicAddress(final String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        final String trimmed = address.trim();

        final String host;
        final String port;
        if (trimmed.startsWith("[")) {
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
     * Returns the address of the standby proxy, which has the host of the address and the standby port.
     *
     * @param address the result of {@link #publicAddress}
     * @param standbyPort {@code network#standby-port}
     * @return the address, or empty when the port is invalid or equals the port of the address
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
