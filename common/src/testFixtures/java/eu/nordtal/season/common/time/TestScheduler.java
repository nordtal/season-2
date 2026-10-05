package eu.nordtal.season.common.time;

/** The real scheduler for a test whose timing is the clock's, one per test JVM and never closed. */
public final class TestScheduler {

    /** Rethrows a run's failure, so that the thread's default handler prints it. */
    public static final Scheduler SHARED = new ProcessScheduler("test", failure -> {
        throw failure;
    });

    private TestScheduler() {}
}
