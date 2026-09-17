package eu.nordtal.s2.common.payment;

import eu.nordtal.s2.common.RepositoryRoot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That {@code eu.nordtal.s2.common.payment} keeps the one property it was carved out for: it knows
 * about a {@code payment_request} row and about nothing else.
 *
 * <h2>Why a text search, and why it is not decoration</h2>
 * This package exists because the payment system is being taken apart along a seam (concept §10d,
 * steward/99): the half that talks to bunq goes to {@code steward-worker}, the half that talks to
 * Discord stays in {@code discord-bot}, and the rows both of them read live here. The seam only
 * holds while <b>neither</b> side leaks into this package.
 *
 * <p>A compiler cannot say that. {@code :common} declares JDBI, HikariCP, slf4j and the PostgreSQL
 * driver and nothing else, so a {@code com.bunq} import fails to compile today - but only by
 * accident of the dependency block, and the fix a hurried session reaches for is to add the
 * dependency. A JDA import is the same story. What this asserts is the rule rather than the
 * accident, and it says in its failure message what the rule is for, so the next session has to
 * argue with the seam instead of with a missing jar.</p>
 *
 * <p>What it cannot catch: a leak that arrives without an import, through a fully qualified name
 * inside a method body. That is deliberate - the same scan over whole file text trips on every
 * javadoc sentence containing the word bunq, and this package's javadoc is full of them, correctly
 * so.</p>
 */
class PaymentCoreStaysNeutralTest {

    /**
     * The package this rule is about. It is inside this module's own main source set, so Gradle
     * already sees an edit to it through {@code :common:compileJava} - unlike the files the other
     * text-reading tests here reach, it needs no {@code repositoryRootTestInputs} declaration.
     */
    private static final String NEUTRAL_CORE =
            "common/src/main/java/eu/nordtal/s2/common/payment";

    /**
     * Package prefixes that must never be imported here, and the sentence each one gets when it is.
     *
     * <p>{@code eu.nordtal.jcore} is in the list for a different reason from the other three: it
     * would compile, because the bot and the worker both have jcore. {@code :common} deliberately
     * does not - see the dependency comment in {@code common/build.gradle.kts} - and one import
     * here would put jcore's whole block (Flyway, gson, snakeyaml, commons-*) behind every plugin
     * that touches a payment row.</p>
     */
    private static final Map<String, String> FORBIDDEN = Map.of(
            "com.bunq.", "the bunq SDK. This package holds the rows; the bank is steward-worker's"
                    + " (concept §10d). A payment row that cannot be read without the bunq SDK on"
                    + " the classpath cannot be read by anything except the process holding the"
                    + " key, which is the whole thing the seam exists to prevent.",
            "net.dv8tion.", "JDA. Discord is discord-bot's half of the seam; a row that needs a"
                    + " gateway session to be understood is not a row.",
            "eu.nordtal.s2.discordbot.", "discord-bot. :common may not depend on a consumer - and"
                    + " this package in particular was moved out of discord-bot so that"
                    + " steward-worker could read the same rows.",
            "eu.nordtal.jcore.", ":common does not have jcore and must not get it for this. Its"
                    + " dependency block is what makes the bot's jar ~31 MB, and every Paper plugin"
                    + " that reads an access row would carry it.");

    @Test
    @DisplayName("the neutral payment core imports neither bunq nor Discord nor jcore")
    void neitherHalfOfTheSeamLeaksIn() throws IOException {
        final List<Path> sources = sources();
        assertFalse(sources.isEmpty(),
                NEUTRAL_CORE + " holds no .java file. Either the package was renamed and this test"
                        + " now guards nothing, or the move it guards was reverted - both of which"
                        + " look like a green build.");

        final List<String> violations = new ArrayList<>();
        for (final Path source : sources) {
            final String name = RepositoryRoot.relative(source);
            for (final String line : Files.readString(source, StandardCharsets.UTF_8).lines().toList()) {
                final String trimmed = line.strip();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                FORBIDDEN.forEach((prefix, why) -> {
                    if (trimmed.startsWith("import " + prefix)
                            || trimmed.startsWith("import static " + prefix)) {
                        violations.add(name + " imports " + why + "\n    " + trimmed);
                    }
                });
            }
        }

        assertTrue(violations.isEmpty(),
                "the neutral payment core is no longer neutral:\n  " + String.join("\n  ", violations));
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
