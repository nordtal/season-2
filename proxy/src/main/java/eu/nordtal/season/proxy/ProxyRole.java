package eu.nordtal.season.proxy;

/** The role of this proxy, set by {@code network#standby}. */
public enum ProxyRole {

    /** The proxy on {@code network#public-address}. */
    LIVE,

    /** The proxy that holds every player during a proxy swap. */
    STANDBY;

    /** Returns {@link #STANDBY} for {@code true}, otherwise {@link #LIVE}. */
    public static ProxyRole of(final boolean standby) {
        return standby ? STANDBY : LIVE;
    }

    /** Returns whether the role is {@link #STANDBY}. */
    public boolean isStandby() {
        return this == STANDBY;
    }
}
