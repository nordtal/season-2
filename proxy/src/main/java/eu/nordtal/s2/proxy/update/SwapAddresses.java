package eu.nordtal.s2.proxy.update;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * The two addresses a proxy swap moves players between.
 *
 * Unresolved, and that is the whole of it. A transfer hands the client a host and a port and the
 * client connects to it itself, so the name has to survive this process untouched. Velocity builds
 * the packet out of {@code InetSocketAddress#getHostName()}, and on an address built from a literal
 * IP that call is a reverse DNS lookup, on whichever thread happens to be holding it. So every
 * address here is built with {@link InetSocketAddress#createUnresolved}, which sets the host
 * string and looks nothing up: the player is told the configured name, and the proxy never asks a
 * resolver what it means.
 *
 * That is also why {@code proxy:25565}, the only address this container knows about itself, is
 * useless here and why the setting cannot have a default: it is a compose service name and resolves
 * nowhere but inside this stack.
 *
 * The port is not optional. A SRV record can hide the port from somebody typing a name into their
 * client. The transfer packet carries host and port, and nothing looks a SRV record up on the
 * client's behalf - so an address without one is refused here rather than silently transferring
 * everybody to port 25565 of a host that may not be listening there.
 */
public final class SwapAddresses {

    private SwapAddresses() {}

    /**
     * Parses {@code network.yml#public-address}.
     *
     * @param address {@code host:port}, or {@code [::1]:port}, or blank
     * @return the address, or empty when it is blank or carries no usable port - which is the
     *         configured way to say "this deployment does not swap proxies", not an error
     */
    public static Optional<InetSocketAddress> publicAddress(final String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        final String trimmed = address.trim();

        final String host;
        final String port;
        if (trimmed.startsWith("[")) {
            // A bare IPv6 literal has colons of its own; the brackets are the only thing saying where the host ends.
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
     * One host and two ports is not a simplification of this feature, it is its shape - see
     * {@code network.yml#standby-port}. A second address would be a second thing to keep in step,
     * and the two proxies are the same image on the same machine.
     *
     * @param address     the parsed {@link #publicAddress}
     * @param standbyPort {@code network.yml#standby-port}
     * @return where to send a player for the length of the swap, or empty when the port is not a
     *         port or is the one the live proxy is already on - which would transfer everybody to
     *         the address they are already connected to
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
