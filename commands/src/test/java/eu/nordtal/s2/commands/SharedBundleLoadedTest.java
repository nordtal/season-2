package eu.nordtal.s2.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 * Later roots win, so the shared one has to come <b>first</b>: a module that wants to reword a shared
 * line does it in its own bundle, and that only works if its own bundle is layered on top.
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

    @Test
    @DisplayName("every process loads the shared bundle, and loads it underneath its own")
    void theSharedRootIsLayeredFirst() throws IOException {
        final List<String> wrong = new ArrayList<>();

        for (final String process : PROCESSES) {
            final String source = read(process);

            // The shared root has to be the FIRST element of the root list, which pins presence and
            // order in one check - and it survives a module naming its own root with a constant,
            // which the bot does.
            if (!source.contains("List.of(\"" + SHARED + "\"")) {
                wrong.add(process + " does not load " + SHARED + " as its first message root."
                        + " Either it does not load the shared bundle at all - in which case every"
                        + " key a shared command names reaches somebody as the key itself - or it"
                        + " loads it last, which would override every line the module reworded.");
            }
        }

        assertEquals(List.of(), wrong);
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
