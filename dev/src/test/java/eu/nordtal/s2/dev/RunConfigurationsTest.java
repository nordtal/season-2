package eu.nordtal.s2.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Holds {@code .run/} against the program it starts, and against the rule that none of it needs bash. */
class RunConfigurationsTest {

    private static final Path RUN = Repository.root(Path.of("")).resolve(".run");

    private static final Pattern PARAMETERS = Pattern.compile("name=\"PROGRAM_PARAMETERS\" value=\"([^\"]*)\"");

    private static final Pattern COMMAND = Pattern.compile("(?m)^  ([a-z]+)(?: \\| ([a-z]+) \\| ([a-z]+))?\\b");

    @Test
    void noConfigurationNeedsBash() throws IOException {
        for (final Path file : configurations()) {
            final String xml = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(xml.contains("ShConfigurationType"), file.getFileName() + " is a shell configuration");
            assertFalse(xml.contains("deploy/dev\""), file.getFileName() + " still runs deploy/dev");
        }
    }

    @Test
    void everyDevConfigurationNamesACommandTheProgramHas() throws IOException {
        final Set<String> commands = helpCommands();
        final List<String> dev = new ArrayList<>();
        for (final Path file : configurations()) {
            final String xml = Files.readString(file, StandardCharsets.UTF_8);
            if (!xml.contains("value=\"eu.nordtal.s2.dev.Dev\"")) {
                continue;
            }
            dev.add(file.getFileName().toString());
            assertTrue(xml.contains("<module name=\"season-2.dev.main\" />"), file.getFileName() + " names no module");
            final Matcher parameters = PARAMETERS.matcher(xml);
            assertTrue(parameters.find(), file.getFileName() + " has no program arguments");
            final String command = parameters.group(1).replaceFirst(" .*", "");
            assertTrue(commands.contains(command), file.getFileName() + " runs '" + command + "', which dev has not");
        }
        assertEquals(30, dev.size(), "the dev: folders hold thirty configurations; .run/README.md says so");
    }

    @Test
    void theHelpNamesEveryCommand() {
        assertEquals(
                Set.of(
                        "init", "up", "deploy", "pack", "ui", "logs", "console", "mc", "psql", "ps", "stop", "down",
                        "reset", "help"),
                helpCommands());
    }

    private static Set<String> helpCommands() {
        final Matcher command = COMMAND.matcher(Dev.HELP);
        final Set<String> found = new java.util.TreeSet<>();
        while (command.find()) {
            for (int group = 1; group <= 3; group++) {
                if (command.group(group) != null) {
                    found.add(command.group(group));
                }
            }
        }
        return found;
    }

    private static List<Path> configurations() throws IOException {
        try (Stream<Path> files = Files.list(RUN)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".run.xml"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }
}
