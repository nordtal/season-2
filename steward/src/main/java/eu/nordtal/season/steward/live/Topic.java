package eu.nordtal.season.steward.live;

/** The topics the browser is told about. A timed topic is read on every pass, and any other topic on a signal. */
public enum Topic {

    /** The run inbox with the list, the open run and every report. */
    RUNS(false),

    /** The requests that Steward writes for the servers and the bot, with their status. */
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

    /** The service table from the last sample of steward-agent. */
    SERVICES(true),

    /** The host numbers. */
    HOST(true),

    /** The newest sampler round in {@code metric_sample}. */
    METRICS(true),

    /** The current alerts and the recently raised alerts. */
    ALERTS(true),

    /** The labels of compose.yml. */
    TOPOLOGY(true),

    /** The game exports of the servers and their icons. */
    GAME_DATA(false),

    /** The command tree that each server published last. */
    COMMANDS(false);

    private final boolean timed;

    Topic(final boolean timed) {
        this.timed = timed;
    }

    boolean timed() {
        return timed;
    }
}
