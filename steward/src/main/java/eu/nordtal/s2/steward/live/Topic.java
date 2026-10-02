package eu.nordtal.s2.steward.live;

/**
 * What the browser can be told has changed, each one or more of the API's reads.
 *
 * A timed topic is read on every pass, since no signal covers it; every other one only on a signal.
 */
public enum Topic {

    /** The run inbox: the list, the open run and every report. */
    RUNS(false),

    /** The requests steward asks the servers and the bot for, and what became of them. */
    REQUESTS(false),

    /** The newest journal line. */
    JOURNAL(false),

    /** The roster, the access periods and the payments. */
    PEOPLE(false),

    /** The season's phase and its two dates. */
    SEASON(false),

    /** The SMP track and the Hunger Games round. */
    GAMES(false),

    /** Every published group of settings and the stored overrides. */
    SETTINGS(false),

    /** The service table, as steward-agent's last sample has it. */
    SERVICES(true),

    /** The host's numbers. */
    HOST(true),

    /** The newest sampler round in {@code metric_sample}. */
    METRICS(true),

    /** What is wrong now, and what was raised lately. */
    ALERTS(true),

    /** What compose.yml's labels say. */
    TOPOLOGY(true),

    /** What the servers exported of the game, and the icons drawn for it. */
    GAME_DATA(false);

    private final boolean timed;

    Topic(final boolean timed) {
        this.timed = timed;
    }

    /** Whether it is read on every pass rather than only when the hub rang. */
    boolean timed() {
        return timed;
    }
}
