package eu.nordtal.s2.steward.deployer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recreate uses the image that is here; deploy is the button that fetches (season-2-ops/134).
 *
 * <p>The bug this holds down was silent in every way a bug can be: the job answered 202, the
 * container came up healthy, the logs were clean, and a locally built {@code steward-ui} had
 * nevertheless been replaced by the published one - {@code md5sum /app/app.jar} {@code df63c328…}
 * before, {@code ef0e1067…} after. Nothing in the interface said a deployment had just been rolled
 * back, because as far as everything downstream was concerned nothing had gone wrong.</p>
 *
 * <p>No docker daemon here, by design: the command line is assembled before anything runs, and the
 * decision not to pull is visible at exactly that point.</p>
 */
class RecreateDoesNotPullTest {

    private final Compose compose = new Compose(
            Path.of("/app/compose.yml"), Path.of("/does/not/exist/.env"), Path.of("/app"), "nordtal-s2");

    @Test
    @DisplayName("the recreate command line fetches nothing, by any of compose's spellings")
    void theCommandLineFetchesNothing() {
        final List<String> command = compose.recreateCommand("steward-ui");

        assertTrue(command.containsAll(List.of("up", "--detach", "--no-deps", "--force-recreate")),
                command.toString());
        assertTrue(command.contains("steward-ui"), command.toString());
        // Not `contains("pull")`: `docker compose up` fetches through `--pull always` as readily as
        // a separate `pull` subcommand does, and a test that only knew the subcommand would let the
        // flag through. Every token, then.
        for (final String token : command) {
            assertFalse(token.contains("pull"), "recreate must not fetch, and this does: " + command);
        }
    }

    /**
     * The other half, and it cannot be asked of {@link Compose}: the fetching used to sit in the
     * route, one call above {@code compose.recreate}. So the subject here is the source of the
     * route's own method - read as text, the way {@code OomLightningRodTest} reads compose.yml,
     * because the alternative is a docker daemon and a registry in a unit test.
     */
    @Test
    @DisplayName("and the route does not pull one line above it, which is where it used to")
    void theRouteDoesNotPullEither() throws IOException {
        final String recreate = methodBody("static int recreate(Compose compose, String service,");
        // The LAST one: `deploy` is overloaded, and the two-line one that only forwards comes
        // first in the file. Matching that one asserted nothing - it caught this test on the way in.
        final String deploy = lastMethodBody("static int deploy(Compose compose, List<String> requested,");

        assertFalse(recreate.contains("compose.pull("),
                "recreate pulls again, which is season-2-ops/134 coming back:\n" + recreate);
        assertTrue(recreate.contains("compose.recreate("), recreate);
        assertTrue(recreate.contains("hasLocalImage"),
                "recreate has to say why it cannot, instead of letting compose fail:\n" + recreate);
        // And the counterweight: if deploy ever stopped pulling, the split this test protects would
        // be gone in the other direction - two buttons that both only use what is already here.
        assertTrue(deploy.contains("compose.pull("),
                "deploy is the button that fetches, and it no longer does:\n" + deploy);
    }

    /** From the line that starts with {@code signature} to the matching closing brace. */
    private static String methodBody(final String signature) throws IOException {
        return body(signature, false);
    }

    /** The same, for a signature that several overloads share; the last one carries the work. */
    private static String lastMethodBody(final String signature) throws IOException {
        return body(signature, true);
    }

    private static String body(final String signature, final boolean last) throws IOException {
        final Path source = repositoryRoot().resolve(
                "steward-deployer/src/main/java/eu/nordtal/s2/steward/deployer/StewardDeployer.java");
        assertTrue(Files.isRegularFile(source), source + " no longer exists - if it moved, this path"
                + " has to move with it, because a missing file is a check that silently stops"
                + " running");
        final String text = Files.readString(source, StandardCharsets.UTF_8);
        final int start = last ? text.lastIndexOf(signature) : text.indexOf(signature);
        assertTrue(start >= 0, "no method starting `" + signature + "` in " + source);
        int depth = 0;
        for (int index = text.indexOf('{', start); index < text.length(); index++) {
            if (text.charAt(index) == '{') {
                depth++;
            } else if (text.charAt(index) == '}' && --depth == 0) {
                return text.substring(start, index + 1);
            }
        }
        throw new AssertionError("unbalanced braces after `" + signature + "`");
    }

    /** Anchors on the directory holding settings.gradle.kts, never on the nearest file by name. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        assertTrue(candidate != null, "no settings.gradle.kts above the working directory");
        return candidate;
    }
}
