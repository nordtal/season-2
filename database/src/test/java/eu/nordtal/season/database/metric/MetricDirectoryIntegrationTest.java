package eu.nordtal.season.database.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.TestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Exercises {@link MetricDirectory} against a real PostgreSQL running the real migrations.
 *
 * Bucketing, averages and the resolution seam are database behaviour; the tests skip without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricDirectoryIntegrationTest {

    /** A fixed instant on an exact UTC hour, so the hour-boundary tests are deterministic. */
    private static final Instant TEN = Instant.parse("2026-08-01T10:00:00Z");

    private static final Instant ELEVEN = Instant.parse("2026-08-01T11:00:00Z");
    private static final Instant TWELVE = Instant.parse("2026-08-01T12:00:00Z");
    private static DataSource dataSource;

    private MetricDirectory metrics;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshTable() {
        execute("DROP TRIGGER IF EXISTS fails ON metric_sample");
        execute("TRUNCATE TABLE metric_sample");
        metrics = MetricDirectory.using(dataSource);
    }

    @Test
    void aBatchComesBackAsItWasWritten() {
        metrics.record(List.of(
                new MetricSample("host", Metric.CPU_PERCENT, TEN, 12.5),
                new MetricSample("host", Metric.CPU_PERCENT, TEN.plusSeconds(30), 18.25),
                new MetricSample("smp", Metric.CPU_PERCENT, TEN, 3.0)));

        final List<MetricPoint> host = metrics.range("host", "cpu_percent", TEN, TEN.plusSeconds(60));
        assertEquals(2, host.size(), "one series, and the other subject's row is not in it");

        assertEquals(TEN, host.get(0).at());
        assertEquals(12.5, host.get(0).value());
        assertEquals(Resolution.RAW, host.get(0).resolution());

        assertEquals(TEN.plusSeconds(30), host.get(1).at(), "oldest first");
        assertEquals(18.25, host.get(1).value());

        assertEquals(
                1, metrics.range("smp", "cpu_percent", TEN, TEN.plusSeconds(60)).size());
    }

    @Test
    void theInstantWrittenIsTheInstantStoredWhateverZoneEitherSideIsIn() {
        // pgjdbc and the server may use different zones; only a literal instant shows a shift.
        metrics.record(
                List.of(new MetricSample("host", Metric.CPU_PERCENT, Instant.parse("2026-08-01T13:37:00Z"), 1.0)));

        assertEquals(
                1, count("SELECT count(*) FROM metric_sample " + "WHERE at = timestamptz '2026-08-01 13:37:00+00'"));
    }

    @Test
    void asecondIdenticalBatchAddsNothing() {
        final List<MetricSample> sweep = List.of(
                new MetricSample("host", Metric.CPU_PERCENT, TEN, 12.5),
                new MetricSample("host", Metric.MEMORY_USED_BYTES, TEN, 4_000_000_000.0));

        metrics.record(sweep);
        metrics.record(sweep);

        assertEquals(
                2,
                count("SELECT count(*) FROM metric_sample"),
                "the key is (subject, metric, resolution, at) and a replayed sweep collides with " + "itself exactly");
    }

    @Test
    void aSampleAlreadyWrittenIsNeverRevisedByALaterOne() {
        metrics.record(List.of(new MetricSample("host", Metric.CPU_PERCENT, TEN, 12.5)));
        metrics.record(List.of(new MetricSample("host", Metric.CPU_PERCENT, TEN, 99.0)));

        assertEquals(
                12.5,
                metrics.range("host", "cpu_percent", TEN, ELEVEN).getFirst().value(),
                "DO NOTHING and not DO UPDATE: a measurement at an instant is a fact");
    }

    @Test
    void anEmptyBatchIsAllowedAndDoesNothing() {
        metrics.record(List.of());
        assertEquals(0, count("SELECT count(*) FROM metric_sample"));
    }

    @Test
    void aValueTheMeanCouldNotSurviveIsRefusedBeforeItReachesTheTable() {
        assertThrows(
                IllegalArgumentException.class, () -> new MetricSample("host", Metric.CPU_PERCENT, TEN, Double.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MetricSample("host", Metric.CPU_PERCENT, TEN, Double.POSITIVE_INFINITY));
    }

    @Test
    void everyResolutionTheCodeCanNameIsOneTheCheckAccepts() throws Exception {
        // Resolution is a Java enum behind a text CHECK; a constant without a migration fails only here.
        for (final Resolution resolution : Resolution.values()) {
            execute("INSERT INTO metric_sample (subject, metric, resolution, at, value) VALUES "
                    + "('host', 'cpu_percent', '" + resolution.name()
                    + "', timestamptz '2026-08-01 10:00:00+00', 1.0)");
        }
        assertEquals(Resolution.values().length, count("SELECT count(*) FROM metric_sample"));
    }

    @Test
    void compactionWritesTheHoursMeanAndTheMeanIsTheMean() {
        // The mean 30 differs from the sum, the median and the mean of any two of them.
        metrics.record(List.of(
                new MetricSample("host", Metric.CPU_PERCENT, TEN, 12.0),
                new MetricSample("host", Metric.CPU_PERCENT, TEN.plusSeconds(1800), 18.0),
                new MetricSample("host", Metric.CPU_PERCENT, TEN.plusSeconds(3540), 60.0),
                new MetricSample("host", Metric.CPU_PERCENT, ELEVEN, 40.0),
                new MetricSample("host", Metric.CPU_PERCENT, ELEVEN.plusSeconds(1800), 60.0)));

        assertEquals(2, metrics.compact(TWELVE), "two whole hours, one row each");

        assertEquals(30.0, hourly("host", "cpu_percent", TEN));
        assertEquals(50.0, hourly("host", "cpu_percent", ELEVEN));
    }

    @Test
    void theHourThatIsStillBeingMeasuredIsNotCompacted() {
        // An hour still in progress must not be compacted, or its second half would be lost.
        metrics.record(List.of(
                new MetricSample("host", Metric.CPU_PERCENT, TEN, 12.0),
                new MetricSample("host", Metric.CPU_PERCENT, TEN.plusSeconds(1800), 18.0),
                new MetricSample("host", Metric.CPU_PERCENT, ELEVEN, 40.0)));

        assertEquals(
                1,
                metrics.compact(ELEVEN.plusSeconds(1800)),
                "only the ten o'clock hour: the cut is rounded down to 11:00");
        assertEquals(15.0, hourly("host", "cpu_percent", TEN));
        assertEquals(
                0,
                count("SELECT count(*) FROM metric_sample WHERE resolution = 'HOUR' "
                        + "AND at = timestamptz '2026-08-01 11:00:00+00'"));
    }

    @Test
    void compactingTwiceLeavesExactlyTheSameRows() {
        metrics.record(List.of(
                new MetricSample("host", Metric.CPU_PERCENT, TEN, 12.0),
                new MetricSample("host", Metric.CPU_PERCENT, TEN.plusSeconds(1800), 18.0),
                new MetricSample("host", Metric.CPU_PERCENT, TEN.plusSeconds(3540), 60.0)));

        assertEquals(1, metrics.compact(ELEVEN));
        final long afterFirst = count("SELECT count(*) FROM metric_sample");

        assertEquals(0, metrics.compact(ELEVEN), "the second run finds the hour already averaged");
        assertEquals(afterFirst, count("SELECT count(*) FROM metric_sample"), "and writes nothing");
        assertEquals(
                30.0,
                hourly("host", "cpu_percent", TEN),
                "and the mean is still the mean of the raw samples, not of itself");

        // Guards against a mean folded into a mean, which changes the value rather than the row count.
        assertEquals(1, count("SELECT count(*) FROM metric_sample WHERE resolution = 'HOUR'"));
    }

    @Test
    void forgetRemovesTheRawRowsThatHaveAMeanAndOnlyThose() {
        metrics.record(List.of(
                new MetricSample("host", Metric.CPU_PERCENT, TEN, 12.0),
                new MetricSample("host", Metric.CPU_PERCENT, TEN.plusSeconds(1800), 18.0),
                new MetricSample("host", Metric.CPU_PERCENT, ELEVEN, 40.0),
                new MetricSample("host", Metric.CPU_PERCENT, ELEVEN.plusSeconds(1800), 60.0)));

        // Only the ten o'clock hour is averaged...
        assertEquals(1, metrics.compact(ELEVEN));

        // ...but the delete is asked to go far past both of them. Age alone would take all four.
        assertEquals(2, metrics.forget(TWELVE), "only the two whose hour has a mean");

        assertEquals(
                0,
                count("SELECT count(*) FROM metric_sample WHERE resolution = 'RAW' "
                        + "AND at < timestamptz '2026-08-01 11:00:00+00'"),
                "the compacted hour's raw rows are gone");
        assertEquals(
                2,
                count("SELECT count(*) FROM metric_sample WHERE resolution = 'RAW' "
                        + "AND at >= timestamptz '2026-08-01 11:00:00+00'"),
                "the hour that was never averaged still has every sample it had - a compaction that "
                        + "failed must not look like one that worked");
    }

    @Test
    void forgettingTwiceRemovesNothingTheSecondTime() {
        metrics.record(List.of(
                new MetricSample("host", Metric.CPU_PERCENT, TEN, 12.0),
                new MetricSample("host", Metric.CPU_PERCENT, TEN.plusSeconds(1800), 18.0)));
        metrics.compact(ELEVEN);

        assertEquals(2, metrics.forget(ELEVEN));
        assertEquals(0, metrics.forget(ELEVEN));
        assertEquals(15.0, hourly("host", "cpu_percent", TEN), "and the mean outlives them, which is the point");
    }

    @Test
    void aWindowSpanningBothResolutionsIsOneCurveWithOnePointPerInstant() {
        metrics.record(List.of(
                new MetricSample("host", Metric.CPU_PERCENT, TEN, 12.0),
                new MetricSample("host", Metric.CPU_PERCENT, TEN.plusSeconds(1800), 18.0),
                new MetricSample("host", Metric.CPU_PERCENT, ELEVEN, 40.0),
                new MetricSample("host", Metric.CPU_PERCENT, ELEVEN.plusSeconds(1800), 60.0),
                new MetricSample("host", Metric.CPU_PERCENT, TWELVE, 100.0)));

        metrics.compact(TWELVE);
        metrics.forget(TWELVE);

        final List<MetricPoint> curve =
                metrics.range("host", "cpu_percent", TEN.minus(Duration.ofHours(1)), TWELVE.plusSeconds(3600));

        assertEquals(3, curve.size(), "two hourly means and the one raw sample that is left");

        assertEquals(TEN, curve.get(0).at());
        assertEquals(15.0, curve.get(0).value());
        assertEquals(Resolution.HOUR, curve.get(0).resolution());

        assertEquals(ELEVEN, curve.get(1).at());
        assertEquals(50.0, curve.get(1).value());
        assertEquals(Resolution.HOUR, curve.get(1).resolution());

        assertEquals(TWELVE, curve.get(2).at());
        assertEquals(100.0, curve.get(2).value());
        assertEquals(
                Resolution.RAW,
                curve.get(2).resolution(),
                "and the caller can see which points are means, because an hour flattens a spike");
    }

    @Test
    void betweenACompactionAndTheDeleteBehindItTheRawSamplesWin() {
        // Between compact and forget an hour exists twice; summing both would double it on the graph.
        metrics.record(List.of(
                new MetricSample("host", Metric.CPU_PERCENT, TEN, 12.0),
                new MetricSample("host", Metric.CPU_PERCENT, TEN.plusSeconds(1800), 18.0),
                new MetricSample("host", Metric.CPU_PERCENT, ELEVEN, 40.0)));
        metrics.compact(ELEVEN);

        final List<MetricPoint> curve = metrics.range("host", "cpu_percent", TEN, TWELVE);

        assertEquals(3, curve.size(), "three raw samples, and the mean of two of them is not a fourth point");
        assertTrue(
                curve.stream().allMatch(p -> p.resolution() == Resolution.RAW),
                "the seam is the oldest raw sample there is, so every hour that still has its raw "
                        + "rows is drawn from them");
        assertEquals(
                List.of(12.0, 18.0, 40.0),
                curve.stream().map(MetricPoint::value).toList());
    }

    @Test
    void andTheyWinEvenWhenNoSampleHappensToSitOnTheHour() {
        // Nothing at exactly 10:00, so the seam is not the oldest raw instant itself.
        metrics.record(List.of(
                new MetricSample("host", Metric.CPU_PERCENT, TEN.plusSeconds(600), 12.0),
                new MetricSample("host", Metric.CPU_PERCENT, TEN.plusSeconds(1800), 18.0),
                new MetricSample("host", Metric.CPU_PERCENT, ELEVEN.plusSeconds(60), 40.0)));
        metrics.compact(ELEVEN);

        final List<MetricPoint> curve = metrics.range("host", "cpu_percent", TEN, TWELVE);

        assertEquals(3, curve.size(), "three raw samples, and the mean of two of them is not a fourth point");
        assertTrue(
                curve.stream().allMatch(p -> p.resolution() == Resolution.RAW),
                "the seam is the hour of the oldest raw sample, not the sample's own minute");
        assertEquals(
                List.of(12.0, 18.0, 40.0),
                curve.stream().map(MetricPoint::value).toList());
    }

    @Test
    void aSeriesWithNothingButMeansIsStillACurve() {
        // With no raw rows, coalesce(min(raw), 'infinity') keeps the seam from being null and the graph empty.
        metrics.record(List.of(
                new MetricSample("host", Metric.CPU_PERCENT, TEN, 12.0),
                new MetricSample("host", Metric.CPU_PERCENT, TEN.plusSeconds(1800), 18.0)));
        metrics.compact(ELEVEN);
        metrics.forget(ELEVEN);

        final List<MetricPoint> curve = metrics.range("host", "cpu_percent", TEN, TWELVE);
        assertEquals(1, curve.size());
        assertEquals(15.0, curve.getFirst().value());
    }

    @Test
    void anEmptyWindowIsEmptyAndNotAnError() {
        metrics.record(List.of(new MetricSample("host", Metric.CPU_PERCENT, TEN, 12.0)));

        assertTrue(metrics.range("host", "cpu_percent", TWELVE, TEN).isEmpty(), "a window that runs backwards");
        assertTrue(metrics.range("host", "cpu_percent", TEN, TEN).isEmpty(), "and one that has collapsed");
        assertTrue(metrics.range("nothing", "cpu_percent", TEN, TWELVE).isEmpty(), "and a series nobody writes");
    }

    @Test
    void toIsExclusiveSoTwoAdjacentWindowsDoNotBothContainThePointBetweenThem() {
        metrics.record(List.of(
                new MetricSample("host", Metric.CPU_PERCENT, TEN, 12.0),
                new MetricSample("host", Metric.CPU_PERCENT, ELEVEN, 40.0)));

        assertEquals(1, metrics.range("host", "cpu_percent", TEN, ELEVEN).size());
        assertEquals(1, metrics.range("host", "cpu_percent", ELEVEN, TWELVE).size());
    }

    @Test
    void aBacklogIsCompactedOneHourPerStatementOldestFirst() {
        recordHours(TEN, 4);
        execute(failingTrigger("INSERT", "timestamptz '2026-08-01 12:00:00+00'"));

        assertThrows(RuntimeException.class, () -> metrics.compact(TEN.plus(Duration.ofHours(4))));

        assertEquals(
                2,
                count("SELECT count(*) FROM metric_sample WHERE resolution = 'HOUR'"),
                "the two hours before the failing one are kept, and the failing hour and the one after are untouched");
        assertEquals(15.0, hourly("host", "cpu_percent", TEN));

        execute("DROP TRIGGER fails ON metric_sample");
        assertEquals(
                2, metrics.compact(TEN.plus(Duration.ofHours(4))), "the next tick takes up where the last stopped");
        assertEquals(4, count("SELECT count(*) FROM metric_sample WHERE resolution = 'HOUR'"));
    }

    @Test
    void aBacklogIsForgottenOneHourPerStatementOldestFirst() {
        recordHours(TEN, 4);
        metrics.compact(TEN.plus(Duration.ofHours(4)));
        execute(failingTrigger("DELETE", "timestamptz '2026-08-01 12:00:00+00'"));

        assertThrows(RuntimeException.class, () -> metrics.forget(TEN.plus(Duration.ofHours(4))));

        assertEquals(
                4,
                count("SELECT count(*) FROM metric_sample WHERE resolution = 'RAW'"),
                "the first two hours' raw rows are gone and the failing hour and the one after it keep theirs");

        execute("DROP TRIGGER fails ON metric_sample");
        assertEquals(4, metrics.forget(TEN.plus(Duration.ofHours(4))));
        assertEquals(0, count("SELECT count(*) FROM metric_sample WHERE resolution = 'RAW'"));
    }

    @Test
    void aBacklogOfSeveralHoursEndsAsOneCallWouldHaveLeftIt() {
        recordHours(TEN, 5);
        final Instant cut = TEN.plus(Duration.ofHours(4));

        assertEquals(4, metrics.compact(cut), "one mean per whole hour before the cut");
        assertEquals(8, metrics.forget(cut), "two raw rows of each of those hours");
        assertEquals(2, count("SELECT count(*) FROM metric_sample WHERE resolution = 'RAW'"));
        assertEquals(0, metrics.compact(cut));
        assertEquals(0, metrics.forget(cut));
    }

    /** Writes two samples, 10 and 20, into each of {@code hours} consecutive UTC hours from {@code first}. */
    private void recordHours(final Instant first, final int hours) {
        final List<MetricSample> samples = new ArrayList<>();
        for (int hour = 0; hour < hours; hour++) {
            final Instant start = first.plus(Duration.ofHours(hour));
            samples.add(new MetricSample("host", Metric.CPU_PERCENT, start, 10.0));
            samples.add(new MetricSample("host", Metric.CPU_PERCENT, start.plusSeconds(1800), 20.0));
        }
        metrics.record(samples);
    }

    /** A trigger that refuses an INSERT of the hourly row, or a DELETE of a raw row, of the hour at {@code hour}. */
    private static String failingTrigger(final String event, final String hour) {
        final String row = event.equals("INSERT") ? "NEW" : "OLD";
        final String kind = event.equals("INSERT") ? "HOUR" : "RAW";
        final String at = event.equals("INSERT") ? row + ".at" : "date_trunc('hour', " + row + ".at)";
        return "CREATE OR REPLACE FUNCTION fail_in_hour() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF " + row + ".resolution = '" + kind + "' AND " + at + " = " + hour
                + " THEN RAISE EXCEPTION 'the statement for this hour fails'; END IF; RETURN " + row + "; END $$; "
                + "CREATE TRIGGER fails BEFORE " + event
                + " ON metric_sample FOR EACH ROW EXECUTE FUNCTION fail_in_hour()";
    }

    private double hourly(final String subject, final String metric, final Instant hour) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery("SELECT value FROM metric_sample WHERE subject = '" + subject + "' "
                                + "AND metric = '" + metric + "' AND resolution = 'HOUR' "
                                + "AND at = timestamptz '" + hour + "'")) {
            assertTrue(rs.next(), "no hourly row at " + hour);
            return rs.getDouble(1);
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private long count(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
