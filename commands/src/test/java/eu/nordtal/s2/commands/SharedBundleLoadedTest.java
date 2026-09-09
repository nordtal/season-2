package eu.nordtal.s2.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That every process adapting a shared command loads the shared bundle underneath its own.
 *
 * <h2>The failure this exists for, which happened while it was being written</h2>
 * A shared command names keys from {@code messages/commands}. A process that loads only its own root
 * cannot resolve them - and {@code Messages} degrades to the key rather than throwing, so
 * {@code /hg start} answers with the literal string {@code hg.start.started} and nothing anywhere
 * says why. It is invisible to every other test: both bundles are internally consistent, both
 * languages carry the same keys, and the command names a key that does exist. It is only wrong at
 * the seam.
 *
 * <p>Moving keys into the shared bundle is what makes a process need this, and moving keys is
 * exactly the change somebody makes while thinking about something else. So it is checked here
 * rather than remembered.</p>
 *
 * <h2>Order matters and is checked</h2>
 * Later roots win, so the shared one has to be layered <b>underneath</b> the module's own: a module
 * that wants to reword a shared line does it in its own bundle, and that only works if its own
 * bundle is on top.
 *
 * <p>Underneath, not <em>first</em>, and the difference stopped being academic on 2026-09-09. The
 * check was "the shared root is the first element of the list" until the two Paper plugins gained a
 * second shared root - {@code messages/paper-common}, the five system lines - which is more general
 * still and therefore sits below this one. Asserting first place would have made adding a third
 * layer a red build for no reason at all, which is how a test teaches somebody to delete it.</p>
 */
class SharedBundleLoadedTest {

    private static final String SHARED = "messages/commands";

    /** Every process that adapts a command. */
    private static final List<String> PROCESSES = List.of(
            "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java",
            "limbo/src/main/java/eu/nordtal/s2/limbo/LimboPlugin.java",
            "network-control/src/main/templates/eu/nordtal/s2/networkcontrol/NetworkControlPlugin.java",
            "discord-bot/src/main/java/eu/nordtal/s2/discordbot/AccessBot.java");

    /** A {@code "messages/..."} literal, in the order the source writes them. */
    private static final Pattern ROOT = Pattern.compile("\"(messages/[a-z-]+)\"");

    @Test
    @DisplayName("every process loads the shared bundle, and loads it underneath its own")
    void theSharedRootIsLayeredUnderneath() throws IOException {
        final List<String> wrong = new ArrayList<>();

        for (final String process : PROCESSES) {
            final List<String> roots = rootsOf(read(process));

            if (!roots.contains(SHARED)) {
                wrong.add(process + " does not load " + SHARED + " at all, so every key a shared"
                        + " command names reaches somebody as the key itself - silently, because"
                        + " Messages degrades to the key rather than throwing.");
                continue;
            }
            // Not last: later roots win, so a shared line has to be underneath something. A process
            // that loads it last overrides every line it has deliberately reworded, and nothing
            // else in the build can see that.
            if (roots.indexOf(SHARED) == roots.size() - 1) {
                wrong.add(process + " loads " + SHARED + " last, so the shared bundle wins over its"
                        + " own. Later roots win: the shared one goes underneath.");
            }
        }

        assertEquals(List.of(), wrong);
    }

    /**
     * Every message root the source names, in order.
     *
     * <p>Read off the source rather than off a loaded {@code Messages}, for the reason this whole
     * class exists: what is being checked is a line in a plugin's {@code onEnable}, and that line
     * only runs on a server.</p>
     */
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
        assertTrue(Files.isRegularFile(source), relative + " no longer exists");
        return Files.readString(source, StandardCharsets.UTF_8);
    }
}
