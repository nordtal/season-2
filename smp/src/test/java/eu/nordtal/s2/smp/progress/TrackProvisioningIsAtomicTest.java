package eu.nordtal.s2.smp.progress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reload writes every milestone row or none of them.
 *
 * <h2>The failure it exists for</h2>
 * {@code ensureObjective} updates the target on conflict - lowering one is the documented escape
 * hatch for an objective that has become impossible - and {@code ObjectiveEngine} reads
 * {@code target} from the database row rather than from the running track. So the provisioning is
 * not the harmless insert of rows nothing looks at yet: it rewrites the arithmetic that decides
 * when an objective is complete and what the aura pot pays out.
 *
 * <p>Statement by statement, a failure in the middle - a connection that stopped answering inside
 * {@code query-timeout-seconds} is the ordinary way to get one - left some objectives carrying the
 * candidate file's targets and the rest the running track's, with {@code track} itself unchanged
 * and the log line saying the reload had been refused. The next unit of progress credited would
 * then complete an objective against a number out of a file that was never applied (CodeRabbit,
 * PR #8).
 *
 * <h2>Why a text search</h2>
 * The half of it that can go wrong is the failure of a statement in the middle of a loop against a
 * real database. What this protects is the one word that makes the loop atomic, and a version
 * without it is indistinguishable from this one on every run that does not fail.
 */
class TrackProvisioningIsAtomicTest {

    private static final String SOURCE = "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java";

    @Test
    @DisplayName("the milestone and objective rows are written in one transaction")
    void provisioningIsOneTransaction() {
        final String source = read();

        final int method = source.indexOf("private void ensureRows(");
        assertTrue(method > 0, "ensureRows has been renamed - this rule moved with it");

        final int transaction = source.indexOf("jdbi.useTransaction(", method);
        assertTrue(transaction > method && transaction - method < 400,
                "ensureRows writes one row per milestone and one per objective one statement at a"
                        + " time, so a failure halfway leaves the database carrying targets from a"
                        + " file the log has just reported as refused");

        final int loop = source.indexOf("for (final Milestone milestone", transaction);
        assertTrue(loop > transaction,
                "the loop has to run inside the transaction, not beside it");
    }

    @Test
    @DisplayName("the track is assigned only after the rows are in")
    void theRowsComeFirst() {
        final String source = read();

        final int rows = source.indexOf("ensureRows(candidate);");
        final int assignment = source.indexOf("track = candidate;", rows);
        assertTrue(rows > 0 && assignment > rows,
                "the rows are written first and the track second (finding 103): with the"
                        + " assignment first, every command read a definition whose objective rows"
                        + " were missing or half written");
    }

    private static String read() {
        try {
            Path candidate = Path.of("").toAbsolutePath();
            while (candidate != null
                    && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                candidate = candidate.getParent();
            }
            if (candidate == null) {
                throw new IllegalStateException("no settings.gradle.kts above the working directory");
            }
            final Path source = candidate.resolve(SOURCE);
            assertTrue(Files.isRegularFile(source), SOURCE + " no longer exists");
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + SOURCE, e);
        }
    }
}
