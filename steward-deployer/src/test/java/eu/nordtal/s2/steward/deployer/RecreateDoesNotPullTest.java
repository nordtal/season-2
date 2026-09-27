package eu.nordtal.s2.steward.deployer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Recreate uses the image that is here; deploy is the button that fetches. */
class RecreateDoesNotPullTest {

    private final Compose compose =
            new Compose(Path.of("/app/compose.yml"), Path.of("/does/not/exist/.env"), Path.of("/app"), "nordtal-s2");

    @Test
    void theRecreateCommandLineFetchesNothingByAnyOfComposesSpellings() {
        final List<String> command = compose.recreateCommand("steward-ui");

        assertTrue(command.containsAll(List.of("up", "--detach", "--no-deps", "--force-recreate")), command.toString());
        assertTrue(command.contains("steward-ui"), command.toString());
        // `docker compose up` fetches through `--pull always` too, so every token is checked.
        for (final String token : command) {
            assertFalse(token.contains("pull"), "recreate must not fetch, and this does: " + command);
        }
    }

    /** The recreate route itself does not pull, read from its source text. */
    @Test
    void andTheRouteDoesNotPullOneLineAboveItEither() throws IOException {
        final String recreate = methodBody("static int recreate(final Compose compose, final String service,");
        // The last one: the short overload that only forwards comes first.
        final String deploy = lastMethodBody("static int deploy(final Compose compose, final List<String> requested,");

        assertFalse(recreate.contains("compose.pull("), "recreate must not pull:\n" + recreate);
        assertTrue(recreate.contains("compose.recreate("), recreate);
        assertTrue(
                recreate.contains("hasLocalImage"),
                "recreate has to say why it cannot, instead of letting compose fail:\n" + recreate);
        // Deploy is the one that has to keep pulling.
        assertTrue(
                deploy.contains("compose.pull("),
                "deploy is the button that fetches, and it no longer does:\n" + deploy);
    }

    /** Returns the method from the line starting with {@code signature} to its closing brace. */
    private static String methodBody(final String signature) throws IOException {
        return body(signature, false);
    }

    /** Returns the last method matching {@code signature}, for overloads. */
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

    // palantir-java-format wraps signatures anywhere; the checks read them as one line.
    private static String joined(final String source) {
        return source.replaceAll("\\(\\s*\\n\\s*", "(")
                .replaceAll("\\s*\\n\\s*\\.", ".")
                .replaceAll("(=|,|->)\\s*\\n\\s*", "$1 ");
    }
}
