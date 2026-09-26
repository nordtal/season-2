package eu.nordtal.s2.proxy;

/**
 * Which of the two proxies this process is (season-2-ops/121).
 *
 * <h2>Why it has to be told and cannot be found out</h2>
 * The two proxies are the same image, the same jars and - deliberately, see {@code compose.yml}'s
 * {@code &proxy-env} anchor - the same environment. They differ by the host port they are published
 * on, and a process cannot see that: Velocity binds {@code 0.0.0.0:25565} inside both containers and
 * Docker does the renumbering outside. So there is nothing to detect, and every attempt to detect it
 * anyway - the hostname, the container name, whether {@code limbo-standby} is up - is a guess that
 * is wrong exactly once, in the minute when being wrong costs the whole network.
 *
 * <p><b>And being wrong is not symmetric.</b> A live proxy that believed it was the standby would
 * watch the public address, find <em>itself</em> answering, and transfer every player to the address
 * they are already connected to, for ever. That is the reason this is one explicit setting with a
 * safe default rather than anything cleverer: {@code network.yml#standby} is false unless somebody
 * said otherwise, and {@code compose.yml} says otherwise in exactly one place.</p>
 *
 * <h2>What the standby does differently, in full</h2>
 * <ul>
 *   <li><b>It puts arrivals in {@code limbo-standby}, not {@code limbo}</b> - even though
 *       {@code limbo} is registered and up, because a proxy swap does not touch the backends. The
 *       player just left the live proxy, and on the live proxy they may well have been standing in
 *       {@code limbo}; sending them to the same backend is the "You are already logged in" the
 *       ticket's own check point warns about.</li>
 *   <li><b>It never releases anybody</b> - see {@code LimboHold#reason}. There is nothing for them
 *       on this proxy: they are here for the twenty seconds the live one takes to come back, and
 *       every backend they could be released onto is one they will be pulled off again.</li>
 *   <li><b>It sends them home by itself</b> - see {@code StandbyReturn}. Not on a signal from
 *       {@code steward-worker}: the run that fails while players are parked here is a run whose
 *       driver is the thing that failed.</li>
 *   <li><b>It writes no player counts</b> - see {@code OnlineWriter}. Two proxies writing
 *       {@code online_count} is two processes overwriting each other's answer, and the standby's
 *       answer is zero for most of its life.</li>
 * </ul>
 */
public enum ProxyRole {

    /** The proxy on {@code network.yml#public-address}; the one players type in. */
    LIVE,

    /**
     * The second proxy, on {@code network.yml#standby-port} of the same host. It exists for the
     * length of one update run and holds every player on the network while it does.
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
