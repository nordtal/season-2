package eu.nordtal.s2.common.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.common.access.AccessSchema;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Exercises {@link MetricDirectory} against a real PostgreSQL running the real migrations.
 *
 * Nothing here has an in-memory stand-in, and the list of things that only a real database can
 * answer is longer for this table than for any other in the module: the idempotence is
 * {@code ON CONFLICT DO NOTHING} on a four-column primary key; the hourly bucket is
 * {@code to_timestamp(floor(epoch / 3600) * 3600)} evaluated by PostgreSQL and held by a CHECK
 * written the same way; {@code avg()} is PostgreSQL's; and the seam between the two resolutions is
 * a scalar {@code min()} inside a UNION ALL. All of it is database behaviour.
 *
 * Testcontainers is driven by hand from {@link BeforeAll}, like every other integration test in
 * this module - the {@code junit-jupiter} extension is built against JUnit 5 and this repo is on
 * the JUnit 6 BOM - and these tests <b>skip themselves</b> when no Docker daemon is reachable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricDirectoryIntegrationTest {

    /** A fixed instant on an exact UTC hour, so the hour-boundary tests are deterministic. */
    private static final Instant TEN = Instant.parse("2026-08-01T10:00:00Z");

    private static final Instant ELEVEN = Instant.parse("2026-08-01T11:00:00Z");
    private static final Instant TWELVE = Instant.parse("2026-08-01T12:00:00Z");

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private MetricDirectory metrics;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed metric tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        AccessSchema.migrate(dataSource);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
        dataSource = null;
    }

    @BeforeEach
    void freshTable() {
        execute("TRUNCATE TABLE metric_sample");
        metrics = MetricDirectory.using(dataSource);
    }

    @Test
    void aBatchComesBackAsItWasWritten() {
        metrics.record(List.of(
                new MetricSample("host", "cpu", TEN, 12.5),
                new MetricSample("host", "cpu", TEN.plusSeconds(30), 18.25),
                new MetricSample("smp", "cpu", TEN, 3.0)));

        final List<MetricPoint> host = metrics.range("host", "cpu", TEN, TEN.plusSeconds(60));
        assertEquals(2, host.size(), "one series, and the other subject's row is not in it");

        assertEquals(TEN, host.get(0).at());
        assertEquals(12.5, host.get(0).value());
        assertEquals(Resolution.RAW, host.get(0).resolution());

        assertEquals(TEN.plusSeconds(30), host.get(1).at(), "oldest first");
        assertEquals(18.25, host.get(1).value());

        assertEquals(1, metrics.range("smp", "cpu", TEN, TEN.plusSeconds(60)).size());
    }

    @Test
    void theInstantWrittenIsTheInstantStoredWhateverZoneEitherSideIsIn() {
        // pgjdbc and the server may use different zones; only a literal instant shows a shift.
        metrics.record(List.of(new MetricSample("host", "cpu", Instant.parse("2026-08-01T13:37:00Z"), 1.0)));

        assertEquals(
                1, count("SELECT count(*) FROM metric_sample " + "WHERE at = timestamptz '2026-08-01 13:37:00+00'"));
    }

    @Test
    void asecondIdenticalBatchAddsNothing() {
        final List<MetricSample> sweep = List.of(
                new MetricSample("host", "cpu", TEN, 12.5), new MetricSample("host", "memory", TEN, 4_000_000_000.0));

        metrics.record(sweep);
        metrics.record(sweep);

        assertEquals(
                2,
                count("SELECT count(*) FROM metric_sample"),
                "the key is (subject, metric, resolution, at) and a replayed sweep collides with " + "itself exactly");
    }

    @Test
    void aSampleAlreadyWrittenIsNeverRevisedByALaterOne() {
        metrics.record(List.of(new MetricSample("host", "cpu", TEN, 12.5)));
        metrics.record(List.of(new MetricSample("host", "cpu", TEN, 99.0)));

        assertEquals(
                12.5,
                metrics.range("host", "cpu", TEN, ELEVEN).getFirst().value(),
                "DO NOTHING and not DO UPDATE: a measurement at an instant is a fact");
    }

    @Test
    void anEmptyBatchIsAllowedAndDoesNothing() {
        metrics.record(List.of());
        assertEquals(0, count("SELECT count(*) FROM metric_sample"));
    }

    @Test
    void aValueTheMeanCouldNotSurviveIsRefusedBeforeItReachesTheTable() {
        assertThrows(IllegalArgumentException.class, () -> new MetricSample("host", "cpu", TEN, Double.NaN));
        assertThrows(
                IllegalArgumentException.class, () -> new MetricSample("host", "cpu", TEN, Double.POSITIVE_INFINITY));
    }

    @Test
    void everyResolutionTheCodeCanNameIsOneTheCheckAccepts() throws Exception {
        // Resolution is a Java enum behind a text CHECK; a constant without a migration fails only here.
        for (final Resolution resolution : Resolution.values()) {
            execute("INSERT INTO metric_sample (subject, metric, resolution, at, value) VALUES " + "('host', 'cpu', '"
                    + resolution.name() + "', timestamptz '2026-08-01 10:00:00+00', 1.0)");
        }
        assertEquals(Resolution.values().length, count("SELECT count(*) FROM metric_sample"));
    }

    @Test
    void compactionWritesTheHoursMeanAndTheMeanIsTheMean() {
        // The mean 30 differs from the sum, the median and the mean of any two of them.
        metrics.record(List.of(
                new MetricSample("host", "cpu", TEN, 12.0),
                new MetricSample("host", "cpu", TEN.plusSeconds(1800), 18.0),
                new MetricSample("host", "cpu", TEN.plusSeconds(3540), 60.0),
                new MetricSample("host", "cpu", ELEVEN, 40.0),
                new MetricSample("host", "cpu", ELEVEN.plusSeconds(1800), 60.0)));

        assertEquals(2, metrics.compact(TWELVE), "two whole hours, one row each");

        assertEquals(30.0, hourly("host", "cpu", TEN));
        assertEquals(50.0, hourly("host", "cpu", ELEVEN));
    }

    @Test
    void theHourThatIsStillBeingMeasuredIsNotCompacted() {
        // An hour still in progress must not be compacted, or its second half would be lost.
        metrics.record(List.of(
                new MetricSample("host", "cpu", TEN, 12.0),
                new MetricSample("host", "cpu", TEN.plusSeconds(1800), 18.0),
                new MetricSample("host", "cpu", ELEVEN, 40.0)));

        assertEquals(
                1,
                metrics.compact(ELEVEN.plusSeconds(1800)),
                "only the ten o'clock hour: the cut is rounded down to 11:00");
        assertEquals(15.0, hourly("host", "cpu", TEN));
        assertEquals(
                0,
                count("SELECT count(*) FROM metric_sample WHERE resolution = 'HOUR' "
                        + "AND at = timestamptz '2026-08-01 11:00:00+00'"));
    }

    @Test
    void compactingTwiceLeavesExactlyTheSameRows() {
        metrics.record(List.of(
                new MetricSample("host", "cpu", TEN, 12.0),
                new MetricSample("host", "cpu", TEN.plusSeconds(1800), 18.0),
                new MetricSample("host", "cpu", TEN.plusSeconds(3540), 60.0)));

        assertEquals(1, metrics.compact(ELEVEN));
        final long afterFirst = count("SELECT count(*) FROM metric_sample");

        assertEquals(0, metrics.compact(ELEVEN), "the second run finds the hour already averaged");
        assertEquals(afterFirst, count("SELECT count(*) FROM metric_sample"), "and writes nothing");
        assertEquals(
                30.0, hourly("host", "cpu", TEN), "and the mean is still the mean of the raw samples, not of itself");

        // Guards against a mean folded into a mean, which changes the value rather than the row count.
        assertEquals(1, count("SELECT count(*) FROM metric_sample WHERE resolution = 'HOUR'"));
    }

    @Test
    void forgetRemovesTheRawRowsThatHaveAMeanAndOnlyThose() {
        metrics.record(List.of(
                new MetricSample("host", "cpu", TEN, 12.0),
                new MetricSample("host", "cpu", TEN.plusSeconds(1800), 18.0),
                new MetricSample("host", "cpu", ELEVEN, 40.0),
                new MetricSample("host", "cpu", ELEVEN.plusSeconds(1800), 60.0)));

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
                new MetricSample("host", "cpu", TEN, 12.0),
                new MetricSample("host", "cpu", TEN.plusSeconds(1800), 18.0)));
        metrics.compact(ELEVEN);

        assertEquals(2, metrics.forget(ELEVEN));
        assertEquals(0, metrics.forget(ELEVEN));
        assertEquals(15.0, hourly("host", "cpu", TEN), "and the mean outlives them, which is the point");
    }

    @Test
    void aWindowSpanningBothResolutionsIsOneCurveWithOnePointPerInstant() {
        metrics.record(List.of(
                new MetricSample("host", "cpu", TEN, 12.0),
                new MetricSample("host", "cpu", TEN.plusSeconds(1800), 18.0),
                new MetricSample("host", "cpu", ELEVEN, 40.0),
                new MetricSample("host", "cpu", ELEVEN.plusSeconds(1800), 60.0),
                new MetricSample("host", "cpu", TWELVE, 100.0)));

        metrics.compact(TWELVE);
        metrics.forget(TWELVE);

        final List<MetricPoint> curve =
                metrics.range("host", "cpu", TEN.minus(Duration.ofHours(1)), TWELVE.plusSeconds(3600));

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
                new MetricSample("host", "cpu", TEN, 12.0),
                new MetricSample("host", "cpu", TEN.plusSeconds(1800), 18.0),
                new MetricSample("host", "cpu", ELEVEN, 40.0)));
        metrics.compact(ELEVEN);

        final List<MetricPoint> curve = metrics.range("host", "cpu", TEN, TWELVE);

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
                new MetricSample("host", "cpu", TEN.plusSeconds(600), 12.0),
                new MetricSample("host", "cpu", TEN.plusSeconds(1800), 18.0),
                new MetricSample("host", "cpu", ELEVEN.plusSeconds(60), 40.0)));
        metrics.compact(ELEVEN);

        final List<MetricPoint> curve = metrics.range("host", "cpu", TEN, TWELVE);

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
                new MetricSample("host", "cpu", TEN, 12.0),
                new MetricSample("host", "cpu", TEN.plusSeconds(1800), 18.0)));
        metrics.compact(ELEVEN);
        metrics.forget(ELEVEN);

        final List<MetricPoint> curve = metrics.range("host", "cpu", TEN, TWELVE);
        assertEquals(1, curve.size());
        assertEquals(15.0, curve.getFirst().value());
    }

    @Test
    void anEmptyWindowIsEmptyAndNotAnError() {
        metrics.record(List.of(new MetricSample("host", "cpu", TEN, 12.0)));

        assertTrue(metrics.range("host", "cpu", TWELVE, TEN).isEmpty(), "a window that runs backwards");
        assertTrue(metrics.range("host", "cpu", TEN, TEN).isEmpty(), "and one that has collapsed");
        assertTrue(metrics.range("nothing", "cpu", TEN, TWELVE).isEmpty(), "and a series nobody writes");
    }

    @Test
    void toIsExclusiveSoTwoAdjacentWindowsDoNotBothContainThePointBetweenThem() {
        metrics.record(
                List.of(new MetricSample("host", "cpu", TEN, 12.0), new MetricSample("host", "cpu", ELEVEN, 40.0)));

        assertEquals(1, metrics.range("host", "cpu", TEN, ELEVEN).size());
        assertEquals(1, metrics.range("host", "cpu", ELEVEN, TWELVE).size());
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
