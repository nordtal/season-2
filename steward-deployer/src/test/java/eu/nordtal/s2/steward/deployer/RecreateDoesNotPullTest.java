package eu.nordtal.s2.steward.deployer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Recreate uses the image that is here; deploy is the button that fetches.
 *
 * A recreate that pulls can silently replace a locally built image with the published one: the
 * job still answers 202, the container still comes up healthy, and nothing downstream can tell a
 * deployment was rolled back.
 *
 * No docker daemon here, by design: the command line is assembled before anything runs, and the
 * decision not to pull is visible at exactly that point.
 */
class RecreateDoesNotPullTest {

    private final Compose compose =
            new Compose(Path.of("/app/compose.yml"), Path.of("/does/not/exist/.env"), Path.of("/app"), "nordtal-s2");

    @Test
    void theRecreateCommandLineFetchesNothingByAnyOfComposesSpellings() {
        final List<String> command = compose.recreateCommand("steward-ui");

        assertTrue(command.containsAll(List.of("up", "--detach", "--no-deps", "--force-recreate")), command.toString());
        assertTrue(command.contains("steward-ui"), command.toString());
        // Not `contains("pull")`: `docker compose up` fetches through `--pull always` too, so every token is checked.
        for (final String token : command) {
            assertFalse(token.contains("pull"), "recreate must not fetch, and this does: " + command);
        }
    }

    /**
     * The other half, which cannot be asked of {@link Compose} alone.
     *
     * The route calling it could fetch on its own, one call above {@code compose.recreate}, so the
     * subject here is the source of the route's own method - read as text, the way
     * {@code OomLightningRodTest} reads compose.yml, because the alternative is a docker daemon and
     * a registry in a unit test.
     */
    @Test
    void andTheRouteDoesNotPullOneLineAboveItEither() throws IOException {
        final String recreate = methodBody("static int recreate(final Compose compose, final String service,");
        // The LAST one: `deploy` is overloaded, and the short one that only forwards comes first.
        final String deploy = lastMethodBody("static int deploy(final Compose compose, final List<String> requested,");

        assertFalse(recreate.contains("compose.pull("), "recreate must not pull:\n" + recreate);
        assertTrue(recreate.contains("compose.recreate("), recreate);
        assertTrue(
                recreate.contains("hasLocalImage"),
                "recreate has to say why it cannot, instead of letting compose fail:\n" + recreate);
        // The counterweight: deploy is the one of the two that has to keep pulling.
        assertTrue(
                deploy.contains("compose.pull("),
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
        final Path source = repositoryRoot()
                .resolve("steward-deployer/src/main/java/eu/nordtal/s2/steward/deployer/StewardDeployer.java");
        assertTrue(
                Files.isRegularFile(source),
                source + " no longer exists - if it moved, this path"
                        + " has to move with it, because a missing file is a check that silently stops"
                        + " running");
        final String text = joined(Files.readString(source, StandardCharsets.UTF_8));
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

    // palantir-java-format wraps a long signature anywhere; the checks read it as one line.
    private static String joined(final String source) {
        return source.replaceAll("\\(\\s*\\n\\s*", "(")
                .replaceAll("\\s*\\n\\s*\\.", ".")
                .replaceAll("(=|,|->)\\s*\\n\\s*", "$1 ");
    }
}
