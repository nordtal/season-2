package eu.nordtal.s2.proxy;

/**
 * Which of the two proxies this process is, set by {@code network.yml#standby}.
 *
 * Both run the same image and environment, so the role cannot be detected and has a safe default.
 */
public enum ProxyRole {

    /** The proxy on {@code network.yml#public-address}, the one players type in. */
    LIVE,

    /** The second proxy, which holds every player for the length of one update run. */
    STANDBY;

    /** Returns the role that {@code network.yml#standby} names. */
    public static ProxyRole of(final boolean standby) {
        return standby ? STANDBY : LIVE;
    }

    /** Whether this process is the standby. */
    public boolean isStandby() {
        return this == STANDBY;
    }
}
