package eu.nordtal.s2.hungergames.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The HUD reads its numbers; nothing has to remember to tell it.
 *
 * What this is guarding: two of the three HUD lines were fed by setters -
 * {@code setCounts(alive, dead)} and {@code setNextRefillAt(instant)} - and <b>neither had a single
 * caller anywhere in the repository</b>. So for the whole of every game the first line read "Alive
 * 0, Dead 0" and the second read "no further refills planned", on the one screen every participant
 * of the season's flagship event is looking at. Seen on the local stack, on a real client, with two
 * live participants standing in the world.
 *
 * Both halves of each wire existed and nothing joined them: {@code WinTracker#aliveCount()} and
 * {@code #deadCount(int)} had no caller either. That is what makes this a shape rather than two
 * slips - "remember to call this whenever something changes" is a rule with no enforcement, and a
 * HUD that redraws four times a second can simply ask instead.
 *
 * The rule: this renderer has no {@code setX} method. Whatever it shows, it pulls from the object
 * that owns the fact, on the redraw. A setter here would compile, pass every other test, and print
 * a zero to everybody.
 */
class HudReadsItsNumbersTest {

    private static final String RENDERER = "hunger-games/src/main/java/eu/nordtal/s2/hungergames/hud/HudRenderer.java";

    private static final Pattern SETTER = Pattern.compile("\\bpublic\\s+void\\s+(set[A-Z]\\w*)\\s*\\(");

    @Test
    void theRendererHasNoSetterForAnybodyToForget() {
        final List<String> found = new ArrayList<>();
        final Matcher matcher = SETTER.matcher(read(RENDERER));
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        assertEquals(
                List.of(),
                found,
                "HudRenderer grew a setter. Two of them had no caller at all and printed zeroes to"
                        + " every participant for the whole of every game; read this class's"
                        + " javadoc before adding a third. Pull the value from whatever owns it,"
                        + " on the redraw.");
    }

    @Test
    void theLivingCountComesFromTheTrackerThatRecordsTheDeaths() {
        final String source = read(RENDERER);
        assertTrue(
                source.contains("wins.aliveCount()") && source.contains("wins.deadCount("),
                "the players line has to read WinTracker, which is the object the death handler"
                        + " writes to - anything else can go stale");
    }

    @Test
    void theRefillTimeComesFromTheScheduleThatOwnsIt() {
        assertTrue(
                read(RENDERER).contains("loot.nextRefillAt()"),
                "the loot line has to read LootRefill rather than a copy somebody pushed in");
    }

    @Test
    void nothingElseInTheModuleKeptAPrivateCopyOfTheCounts() {
        final List<String> offenders = new ArrayList<>();
        for (final Path source : sources()) {
            final String relative =
                    repositoryRoot().relativize(source).toString().replace('\\', '/');
            // The renderer's own javadoc names both retired setters; the first test above keeps them from returning.
            if (relative.equals(RENDERER)) {
                continue;
            }
            final String text = read(relative);
            if (text.contains("setCounts(") || text.contains("setNextRefillAt(")) {
                offenders.add(source.getFileName().toString());
            }
        }
        assertEquals(
                List.of(),
                offenders,
                "the push-based setters are gone; a call to one is a merge that brought them back");
    }

    private static List<Path> sources() {
        final Path directory = repositoryRoot().resolve("hunger-games/src/main/java");
        final List<Path> found = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(directory)) {
            tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .forEach(found::add);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot walk the module's sources", e);
        }
        return found;
    }

    private static String read(final String relative) {
        try {
            return Files.readString(repositoryRoot().resolve(relative), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + relative, e);
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above the working directory");
        }
        return candidate;
    }
}
