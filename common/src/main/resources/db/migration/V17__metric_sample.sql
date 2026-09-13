-- The time series behind Steward's start page (concept §10c, decided 2026-09-12).
--
-- steward-worker writes the samples and steward-ui reads the curves, and the two never talk: the
-- interface reads its graphs OUT OF POSTGRES and not out of Docker. That is the decision this table
-- exists to carry out. Docker's /containers/{id}/stats answers "now" and nothing else - there is no
-- history behind it - so a page that drew its curves from Docker would draw a single point and call
-- it a line. Only two live streams remain through the worker after this, and both are genuinely
-- live: the log and the console.
--
--
-- HOW BIG THIS GETS, MEASURED ON THIS HOST ON 2026-09-12
--
-- A sample every 30 seconds, eleven series (the host plus nine or ten containers), so 2880 points
-- per day and series and roughly 32 000 rows a day. After 30 days that is just under a million rows
-- and of the order of 60 MB. That number is the whole reason the rest of this file exists.
--
-- The 60 MB is the ROWS, and it is right: 950 400 rows loaded into exactly this table on
-- PostgreSQL 17.11 on 2026-09-12 measured 59 MB of heap. What the estimate leaves out is the
-- indexes, which are larger than the table - 71 MB for the primary key and 8.8 MB for the partial
-- index below, so 138 MB in total. A key of (text, text, text, timestamptz) repeated a million
-- times is most of that. It does not change any decision here, but it is the number to quote at
-- whoever next asks what a month of history costs, and it more than doubles the estimate above.
--
--
-- THE RETENTION IS A DECISION ABOUT THE BACKUP, NOT ONLY ABOUT THIS TABLE
--
-- This table is in the same database the nightly backup dumps, so it is inside every snapshot, and
-- every snapshot is kept. Raw samples kept forever would therefore not cost 60 MB a month once -
-- they would cost 60 MB a month in the live database AND a growing share of every backup taken
-- from then on. That is how a monitoring table quietly becomes the largest thing in a restore.
--
-- So: raw samples live 30 days, and are then compacted to HOURLY MEANS. An hour of one series is
-- 120 raw points and becomes 1, which shrinks the same month to a thirtieth - about 2 MB - and
-- still keeps the shape of the year. Whoever changes the 30 days, or decides to keep raw samples
-- longer, is deciding how large every future backup is, and should say so in the same breath.
--
-- The half-measure that was NOT taken: a second table for the hourly means. It would need its own
-- indexes, its own mapper and a UNION in every read, to hold rows with exactly the same columns as
-- these. One table with a `resolution` column costs one more column in the primary key and lets
-- `range` answer across the seam with a UNION ALL of two index scans on the same index.
CREATE TABLE metric_sample
(
    subject    text             NOT NULL,
    metric     text             NOT NULL,
    resolution text             NOT NULL,
    at         timestamptz      NOT NULL,
    value      double precision NOT NULL,

    -- (subject, metric, resolution, at), in that order, and the order is the whole point.
    --
    -- It is both the identity of a sample and the index the first real query needs: "one series
    -- over a time range" is equality on subject, metric and resolution followed by a range on `at`,
    -- which is exactly this index read forwards. Putting `at` first - the shape a time-series table
    -- is often given - would make every such read scan every series.
    --
    -- As an identity it is also what makes a write idempotent: the collector inserts with
    -- ON CONFLICT DO NOTHING, so a batch replayed after a crash, or a run that overlaps the
    -- previous one, adds nothing. A sample at an instant is a fact and is never revised.
    CONSTRAINT metric_sample_pkey PRIMARY KEY (subject, metric, resolution, at),

    CONSTRAINT metric_sample_resolution_check
        CHECK (resolution IN ('RAW', 'HOUR')),

    -- Bounded rather than free text, because these are identifiers and not prose: `subject` is
    -- 'host' or a compose service name, `metric` is something like 'cpu' or 'memory.bytes'. An
    -- empty one is a collector bug, and it would be a bug that produced a series nobody could name.
    CONSTRAINT metric_sample_subject_check CHECK (length(subject) BETWEEN 1 AND 64),
    CONSTRAINT metric_sample_metric_check CHECK (length(metric) BETWEEN 1 AND 64),

    -- Finite, and this is not decoration.
    --
    -- PostgreSQL's double precision accepts 'NaN' and '±Infinity', and avg() propagates them: one
    -- NaN sample - a percentage computed from a container that reported a zero time delta, say -
    -- would turn that hour's mean into NaN, the raw samples behind it would then be deleted by
    -- `forget`, and the NaN would be all that was left of the hour. Rejecting it at the door means
    -- the collector fails loudly at the moment it computes nonsense.
    --
    -- Written as two bounds rather than with isfinite() because that is what works on a CHECK:
    -- in PostgreSQL NaN sorts ABOVE every number, so `value < 'Infinity'` excludes NaN and +Inf
    -- together (verified against this deployment's PostgreSQL 17.11, 2026-09-12).
    CONSTRAINT metric_sample_finite_check
        CHECK (value > '-Infinity'::double precision AND value < 'Infinity'::double precision),

    -- An HOUR row sits exactly on a UTC hour boundary, and a RAW row may sit anywhere.
    --
    -- This is what stops the two resolutions from being mixed up by a writer: an hourly mean whose
    -- timestamp is the middle of its hour would still be a valid row, would still be read back, and
    -- would put every compacted point half an hour late on the graph.
    --
    -- The bucket is computed from the epoch rather than with date_trunc('hour', ...) for one
    -- concrete reason: date_trunc on a timestamptz is STABLE and not IMMUTABLE, because zones with
    -- a sub-hour offset (+05:45, +05:30) make the answer depend on the session's TimeZone, and
    -- PostgreSQL refuses a non-immutable function in a CHECK. floor(epoch / 3600) has no zone in
    -- it at all, which also settles the question the other way round: the buckets are UTC hours,
    -- everywhere, whatever the JVM or the session thinks the local time is.
    CONSTRAINT metric_sample_hour_is_aligned_check
        CHECK (resolution <> 'HOUR'
            OR at = to_timestamp(floor(extract(epoch FROM at) / 3600) * 3600))
);

COMMENT ON TABLE metric_sample IS
    'Time series for Steward''s start page: one row per subject, metric and instant. Raw samples '
        'every 30s are compacted to hourly means after 30 days and then deleted - a retention that '
        'is also a decision about the size of every backup. See V17.';

COMMENT ON COLUMN metric_sample.subject IS
    'What was measured: the literal ''host'', or a compose service name (smp, limbo, postgres...). '
        'Not a container id - a container is replaced on every deploy and the curve must not be.';

COMMENT ON COLUMN metric_sample.metric IS
    'Which number: cpu, memory bytes, load, disk used. Free-form by design - the collector may add '
        'one without a migration, and a metric nothing writes any more simply stops having rows.';

COMMENT ON COLUMN metric_sample.resolution IS
    'RAW is one measurement at that instant. HOUR is the mean of the raw samples of the UTC hour '
        'beginning at `at`, written by compaction after 30 days. The two live in one table so that '
        'a read spanning the seam is one index scan each and not a join.';

COMMENT ON COLUMN metric_sample.at IS
    'For RAW, when it was measured. For HOUR, the START of the hour it averages - always exactly '
        'on a UTC hour, enforced above.';

COMMENT ON COLUMN metric_sample.value IS
    'The measurement, or the arithmetic mean of the hour''s measurements. Always finite: NaN and '
        'infinities are refused, because avg() would carry one into the mean that outlives the raw '
        'rows it was computed from.';


-- The second real query: "everything raw older than X", for compaction and for the delete that
-- follows it.
--
-- The primary key cannot serve it - its leading column is `subject`, and this query has no subject
-- at all - so without this index compaction is a sequential scan of the whole table every night.
--
-- Partial, on `at` alone. `resolution = 'RAW'` moves into the index predicate instead of being a
-- key column, which keeps the index to the rows it is ever asked about and keeps it exactly one
-- column wide. It is effectively "the raw rows, in age order", which is both what the compaction
-- reads and what the delete after it reads.
--
-- It is a second index on a table taking 32 000 inserts a day, which is the cost. Measured against
-- the alternative that is a full scan of a million rows once an hour, it is not a close call.
CREATE INDEX metric_sample_raw_by_age
    ON metric_sample (at)
    WHERE resolution = 'RAW';

COMMENT ON INDEX metric_sample_raw_by_age IS
    'The raw rows in age order - what compaction and the delete behind it read. The primary key '
        'starts at `subject` and cannot answer a query that names none.';
