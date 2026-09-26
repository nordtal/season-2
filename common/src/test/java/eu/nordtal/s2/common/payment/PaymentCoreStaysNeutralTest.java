package eu.nordtal.s2.common.payment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.RepositoryRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@code eu.nordtal.s2.common.payment} imports neither bunq nor JDA.
 *
 * The bunq half lives in steward-worker and the Discord half in discord-bot; this package holds only the
 * rows. A fully qualified name inside a method body is not caught.
 */
class PaymentCoreStaysNeutralTest {

    /** The package this rule is about, in this module's main source set, so no extra test input is needed. */
    private static final String NEUTRAL_CORE = "common/src/main/java/eu/nordtal/s2/common/payment";

    /**
     * Package prefixes that must never be imported here, and the sentence each one gets when it is.
     *
     * {@code eu.nordtal.jcore} is in the list for a different reason from the other three: it
     * would compile, because the bot and the worker both have jcore. {@code :common} deliberately
     * does not - see the dependency comment in {@code common/build.gradle.kts} - and one import
     * here would put jcore's whole block (Flyway, gson, snakeyaml, commons-*) behind every plugin
     * that touches a payment row.
     */
    private static final Map<String, String> FORBIDDEN = Map.of(
            "com.bunq.",
                    "the bunq SDK. This package holds the rows; the bank is steward-worker's"
                            + " (concept §10d). A payment row that cannot be read without the bunq SDK on"
                            + " the classpath cannot be read by anything except the process holding the"
                            + " key, which is the whole thing the seam exists to prevent.",
            "net.dv8tion.",
                    "JDA. Discord is discord-bot's half of the seam; a row that needs a"
                            + " gateway session to be understood is not a row.",
            "eu.nordtal.s2.discordbot.",
                    "discord-bot. :common may not depend on a consumer - and"
                            + " this package in particular was moved out of discord-bot so that"
                            + " steward-worker could read the same rows.",
            "eu.nordtal.jcore.",
                    ":common does not have jcore and must not get it for this. Its"
                            + " dependency block is what makes the bot's jar ~31 MB, and every Paper plugin"
                            + " that reads an access row would carry it.");

    @Test
    void theNeutralPaymentCoreImportsNeitherBunqNorDiscordNorJcore() throws IOException {
        final List<Path> sources = sources();
        assertFalse(
                sources.isEmpty(),
                NEUTRAL_CORE + " holds no .java file. Either the package moved and this test guards"
                        + " nothing, or the move it guards was reverted - both of which"
                        + " look like a green build.");

        final List<String> violations = new ArrayList<>();
        for (final Path source : sources) {
            final String name = RepositoryRoot.relative(source);
            for (final String line :
                    Files.readString(source, StandardCharsets.UTF_8).lines().toList()) {
                final String trimmed = line.strip();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                FORBIDDEN.forEach((prefix, why) -> {
                    if (trimmed.startsWith("import " + prefix) || trimmed.startsWith("import static " + prefix)) {
                        violations.add(name + " imports " + why + "\n    " + trimmed);
                    }
                });
            }
        }

        assertTrue(
                violations.isEmpty(), "the neutral payment core is not neutral:\n  " + String.join("\n  ", violations));
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> tree = Files.walk(RepositoryRoot.resolve(NEUTRAL_CORE))) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }
}
