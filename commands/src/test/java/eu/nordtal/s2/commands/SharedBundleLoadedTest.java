package eu.nordtal.s2.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every process adapting a shared command loads the shared bundle underneath its own.
 *
 * {@code Messages} degrades to the key, so a missing or misordered root shows only as a raw key in chat.
 */
class SharedBundleLoadedTest {

    private static final String SHARED = "messages/commands";

    /** Every process that adapts a command. */
    private static final List<String> PROCESSES = List.of(
            "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java",
            "limbo/src/main/java/eu/nordtal/s2/limbo/LimboPlugin.java",
            "proxy/src/main/templates/eu/nordtal/s2/proxy/ProxyPlugin.java",
            "discord-bot/src/main/java/eu/nordtal/s2/discordbot/AccessBot.java");

    /** A {@code "messages/..."} literal, in the order the source writes them. */
    private static final Pattern ROOT = Pattern.compile("\"(messages/[a-z-]+)\"");

    @Test
    void everyProcessLoadsTheSharedBundleAndLoadsItUnderneathItsOwn() throws IOException {
        final List<String> wrong = new ArrayList<>();

        for (final String process : PROCESSES) {
            final List<String> roots = rootsOf(read(process));

            if (!roots.contains(SHARED)) {
                wrong.add(process + " does not load " + SHARED + " at all, so every key a shared"
                        + " command names reaches somebody as the key itself - silently, because"
                        + " Messages degrades to the key rather than throwing.");
                continue;
            }
            // Later roots win, so the shared bundle must not be last.
            if (roots.indexOf(SHARED) == roots.size() - 1) {
                wrong.add(process + " loads " + SHARED + " last, so the shared bundle wins over its"
                        + " own. Later roots win: the shared one goes underneath.");
            }
        }

        assertEquals(List.of(), wrong);
    }

    /** Every message root the source names, in order, read off the source since the wiring only runs on a server. */
    private static List<String> rootsOf(final String source) {
        final List<String> roots = new ArrayList<>();
        final Matcher matcher = ROOT.matcher(source);
        while (matcher.find()) {
            roots.add(matcher.group(1));
        }
        return roots;
    }

    private static String read(final String relative) throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above the working directory");
        }
        final Path source = candidate.resolve(relative);
        assertTrue(Files.isRegularFile(source), relative + " is missing");
        // A plugin may delegate its start to a sibling <Name>Start.java; the wiring is read from both.
        final Path start = source.resolveSibling(source.getFileName().toString().replace("Plugin.java", "Start.java"));
        final String own = Files.readString(source, StandardCharsets.UTF_8);
        return Files.isRegularFile(start) && !start.equals(source)
                ? own + "\n" + Files.readString(start, StandardCharsets.UTF_8)
                : own;
    }
}
