package eu.nordtal.s2.common.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.RepositoryRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * That all three Paper backends run the command filter, and that the proxy publishes what they read.
 *
 * <b>Why this is a text search and not a real test</b>
 *
 * The same reason {@code AdminWatchWiringTest} is one, and it is the same failure it guards against:
 * a backend that never registers the filter looks <b>exactly</b> like one that does. Nothing about
 * the server is different - the plugin loads, every command works, the tab list is full - and the
 * only observable difference is a vanilla command in somebody's completion, which is what the whole
 * thing exists to remove. There is no assertion available from a JVM with no server in it.
 *
 * The proxy is in this list too, for the half that has no second chance: it is the only process
 * that writes the row, so a proxy that stops publishing leaves three servers filtering whatever they
 * last read, for as long as that row survives - which is silently, and for ever.
 */
class CommandFilterWiringTest {

    private static final List<String> PAPER_PLUGINS = List.of(
            "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java",
            "limbo/src/main/java/eu/nordtal/s2/limbo/LimboPlugin.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java");

    private static final String PROXY = "proxy/src/main/templates/eu/nordtal/s2/proxy/ProxyPlugin.java";

    @Test
    void everyBackendBuildsTheFilterRegistersItAsAListenerAndStartsItsPoll() throws IOException {
        for (final String relative : PAPER_PLUGINS) {
            final String text = read(relative);

            assertTrue(
                    text.contains("new eu.nordtal.s2.papercommon.command.CommandFilter(")
                            || text.contains("new CommandFilter("),
                    relative + " does not build a CommandFilter, so every vanilla command on this"
                            + " server is still offered to every player in tab completion.");
            assertTrue(
                    text.contains("registerEvents(commandFilter"),
                    relative + " builds a CommandFilter and never registers it as a listener, which"
                            + " is the same as not having one and looks like having one.");
            assertTrue(
                    text.contains("commandFilter.start("),
                    relative + " never starts the filter's poll. The poll is the guarantee - without"
                            + " it the list only ever arrives on a notification, and a notification"
                            + " missed while this server was starting is one nothing asks for"
                            + " again.");
            assertTrue(
                    text.contains("commandFilter.refreshes()") && text.contains("commandFilter.channels()"),
                    relative + " does not put the allowlist channel on the admin watcher's LISTEN"
                            + " connection, so an edit takes a whole poll interval to arrive here"
                            + " while it is instant on the proxy.");
        }
    }

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
