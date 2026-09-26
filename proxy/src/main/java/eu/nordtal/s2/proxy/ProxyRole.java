package eu.nordtal.s2.proxy;

/**
 * Which of the two proxies this process is.
 *
 * The two proxies are the same image, the same jars and, deliberately (see {@code compose.yml}'s
 * {@code &proxy-env} anchor), the same environment. They differ only by the host port they are
 * published on, and a process cannot see that: Velocity binds {@code 0.0.0.0:25565} inside both
 * containers and Docker does the renumbering outside. So there is nothing to detect, and every
 * attempt to detect it anyway - the hostname, the container name, whether {@code limbo-standby} is
 * up - is a guess that is wrong exactly once, in the minute when being wrong costs the whole
 * network.
 *
 * Being wrong is not symmetric: a live proxy that believed it was the standby would watch the
 * public address, find itself answering, and transfer every player to the address they are
 * already connected to, forever. That is why this is one explicit setting with a safe default:
 * {@code network.yml#standby} is false unless somebody said otherwise, and {@code compose.yml}
 * says otherwise in exactly one place.
 *
 * The standby differs in what it does with a parked player: it puts arrivals in
 * {@code limbo-standby}, not {@code limbo}, because a proxy swap does not touch the backends and
 * the player may already be standing in {@code limbo} on the live proxy; it never releases anybody,
 * since there is nothing on this proxy for them (see {@code LimboHold#reason}); it sends them home
 * by itself rather than waiting on a signal from {@code steward-worker} (see
 * {@code StandbyReturn}); and it writes no player counts, since two proxies writing
 * {@code online_count} would overwrite each other's answer (see {@code OnlineWriter}).
 */
public enum ProxyRole {

    /** The proxy on {@code network.yml#public-address}; the one players type in. */
    LIVE,

    /**
     * The second proxy, on {@code network.yml#standby-port} of the same host.
     *
     * It exists for the length of one update run and holds every player on the network while it does.
     */
    STANDBY;

    /**
     * @param standby {@code network.yml#standby}
     * @return the role that flag names
     */
    public static ProxyRole of(final boolean standby) {
        return standby ? STANDBY : LIVE;
    }

    /** @return whether this process is the standby - read it rather than comparing enum constants */
    public boolean isStandby() {
        return this == STANDBY;
    }
}
