package eu.nordtal.season.database.notify;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.RepositoryRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every channel any module's SQL notifies on is a {@link Channel}, so no signal goes out on a name nobody knows. */
class ChannelTest {

    private static final Pattern EMITTED = Pattern.compile("pg_notify\\('([a-z0-9_]+)'|NOTIFY ([a-z0-9_]+)");

    @Test
    void everyChannelAnyModuleEmitsIsAConstant() throws IOException {
        final Set<String> known =
                Arrays.stream(Channel.values()).map(Channel::sqlName).collect(Collectors.toSet());
        final Set<String> emitted = new TreeSet<>();
        for (final Path main : everyModulesMain()) {
            try (Stream<Path> files = Files.walk(main)) {
                for (final Path file : files.filter(ChannelTest::isSource).toList()) {
                    final Matcher matcher = EMITTED.matcher(Files.readString(file));
                    while (matcher.find()) {
                        emitted.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
                    }
                }
            }
        }

        assertFalse(emitted.isEmpty(), "found no notification at all, so the search itself is broken");
        emitted.removeAll(known);
        assertTrue(emitted.isEmpty(), "notified on channels that are not in Channel: " + emitted);
    }

    /** Returns the {@code src/main} of every module, which the build declares as inputs of this test. */
    private static List<Path> everyModulesMain() throws IOException {
        try (Stream<Path> modules = Files.list(RepositoryRoot.path())) {
            return modules.map(module -> module.resolve("src/main"))
                    .filter(Files::isDirectory)
                    .toList();
        }
    }

    private static boolean isSource(final Path file) {
        final String name = file.getFileName().toString();
        return Files.isRegularFile(file) && (name.endsWith(".java") || name.endsWith(".sql"));
    }
}
