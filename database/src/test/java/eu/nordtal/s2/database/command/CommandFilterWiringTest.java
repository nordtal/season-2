package eu.nordtal.s2.database.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.RepositoryRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Checks that all three Paper backends run the command filter and that the proxy publishes what they read.
 *
 * A text search, since a backend without the filter looks exactly like one with it.
 */
class CommandFilterWiringTest {

    private static final String PROXY = "proxy/src/main/templates/eu/nordtal/s2/proxy/ProxyPlugin.java";

    @Test
    void theProxyPublishesTheListTheBackendsRead() throws IOException {
        final String text = read(PROXY);
        assertTrue(
                text.contains("AllowlistDirectory.using(pool).publish("),
                PROXY + " no longer publishes the command allowlist. The three backends read it"
                        + " from the database and nothing else writes it, so they would keep"
                        + " filtering against whatever a previous version left in the row.");
        assertTrue(
                text.contains("new CommandGate("),
                PROXY + " does not register the CommandGate, so /server is open to every player"
                        + " again - which is the finding this whole list exists for.");
        assertTrue(
                text.contains("new RouteIntents("),
                PROXY + " does not register RouteIntents, so a connection nothing in this plugin"
                        + " chose is accepted - the layer underneath the command filter.");
    }

    private static String read(final String relative) throws IOException {
        final Path path = RepositoryRoot.resolve(relative);
        assertTrue(
                Files.isRegularFile(path),
                relative + " is missing - a renamed module has to move with this list, because a"
                        + " missing file is a check that silently stops running");
        // A plugin may delegate its start to a sibling <Name>Start.java; the wiring is read from both.
        final Path start = path.resolveSibling(path.getFileName().toString().replace("Plugin.java", "Start.java"));
        final String own = Files.readString(path, StandardCharsets.UTF_8);
        return Files.isRegularFile(start) && !start.equals(path)
                ? own + "\n" + Files.readString(start, StandardCharsets.UTF_8)
                : own;
    }
}
