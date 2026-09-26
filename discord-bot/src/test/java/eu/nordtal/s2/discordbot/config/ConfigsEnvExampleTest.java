package eu.nordtal.s2.discordbot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigValidationException;
import eu.nordtal.jcore.config.spec.annotation.Protected;
import eu.nordtal.s2.common.config.EnvOverrideFile;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How {@code access.yml}'s settings survive the round trip through {@code .env.example} and the environment overlay.
 *
 * Split out of {@link ConfigsTest}, which owns the file-loading rules this reuses only as a fixture.
 */
class ConfigsEnvExampleTest {

    private static final String VALID_TIERS = """
            tiers:
            - days: 30
              price-cents: 300
            - days: 60
              price-cents: 500
            - days: 90
              price-cents: 700""";

    private static final String VALID_LANGUAGES = """
            languages:
            - tag: en
              role: '30'
              contribution-channel: '31'
              link-channel: '32'
              hunger-games-channel: '39'
            - tag: de
              role: '33'
              contribution-channel: '34'
              link-channel: '35'
              hunger-games-channel: '40'""";

    private static final String REST = """
            guild-id: '1'
            donation-cents: 500
            roles:
              access: '10'
              donor: '11'
              admin: '14'
              admin-ping: '15'
            channels:
              admin: '24'
            payment:
              poll-interval-seconds: 30
              request-ttl-hours: 24
            expiry-reminder-lead-days: 3
            role-reconcile-interval-minutes: 10
            """;

    /** A complete, valid access.yml. */
    private static String access() {
        return VALID_TIERS + "\n" + VALID_LANGUAGES + "\n" + REST;
    }

    @TempDir
    Path directory;

    @BeforeEach
    void pointConfigsAtTempDirectory() {
        System.setProperty(Configs.DIRECTORY_PROPERTY, directory.toString());
    }

    @AfterEach
    void restore() {
        System.clearProperty(Configs.DIRECTORY_PROPERTY);
    }

    // .env.example.

    /**
     * The value of {@code key} as {@code .env.example} at the repository root ships it, with a leading {@code #}
     * stripped from every line of a commented-out block.
     *
     * Read out of the real file rather than copied into a literal here, on purpose: a copy is a second source of truth
     * that nothing compares, and the two blocks this reads are exactly the ones whose shape has to survive jcore's
     * environment overlay. {@code build-logic} 's {@code repositoryRootTestInputs} declares the file as an input of
     * this
     * test task - without that, editing {@code .env.example} would leave {@code :discord-bot:test} UP-TO-DATE and the
     * check would not run at all.
     *
     * Both blocks are written the way an operator reads them - one key per line - rather than as the single line a
     * shell
     * would have needed, because docker compose parses a single-quoted multi-line value in an env file as one string.
     * That is a claim about two systems this repository does not own, so the half that is ours is what is checked:
     * whatever compose hands over, jcore has to turn back into a list of specs.
     */
    private static String envExampleValue(final String key) throws IOException {
        final Path file = repositoryRoot().resolve(".env.example");
        assertTrue(Files.isRegularFile(file), file + " does not exist");
        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        final String opening = key + "='";

        for (int i = 0; i < lines.size(); i++) {
            final StringBuilder value = new StringBuilder(uncomment(lines.get(i)));
            if (!value.toString().startsWith(opening)) {
                continue;
            }
            value.delete(0, opening.length());
            while (!value.toString().endsWith("'")) {
                if (++i == lines.size()) {
                    throw new IllegalStateException(file + " never closes the quote on " + key);
                }
                value.append('\n').append(uncomment(lines.get(i)));
            }
            return value.substring(0, value.length() - 1);
        }
        throw new IllegalStateException(file + " does not set " + key + " any more. If it was"
                + " renamed, rename it here too - this test is the only thing that reads it.");
    }

    /** A commented-out block is still the value an operator uncomments; the {@code #} is not. */
    private static String uncomment(final String line) {
        return line.startsWith("#") ? line.substring(1) : line;
    }

    /**
     * The repository root, found by walking up from the working directory until {@code settings.gradle.kts} is there.
     *
     * Gradle sets the working directory to the module folder and IntelliJ may not.
     *
     * It anchors on the build rather than on the first {@code .env.example} above it, because this module used to ship
     * one of its own next to its old compose file: a search for the nearest file by name found that one, which is the
     * wrong file and looks like the right one.
     */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }

    @Test
    void theLanguageListInEnvExampleIsReadBackAsTwoLanguages() throws Exception {
        final AccessSpec config =
                fromEnvironment(Map.of("NORDTAL_ACCESS_LANGUAGES", envExampleValue("NORDTAL_ACCESS_LANGUAGES")));

        assertEquals(2, config.languages().size(), "both entries have to survive the round trip");
        assertEquals("en", config.languages().get(0).tag());
        assertEquals("de", config.languages().get(1).tag());
        // A JSON key that does not match the @Key name comes back null; REPLACE_ME proves the names line up.
        assertEquals("REPLACE_ME", config.languages().get(0).role());
        assertEquals("REPLACE_ME", config.languages().get(0).contributionChannel());
        assertEquals("REPLACE_ME", config.languages().get(0).linkChannel());
        assertEquals("REPLACE_ME", config.languages().get(1).hungerGamesChannel());
        // status-channel is empty rather than REPLACE_ME - it is the one id an operator may leave out.
        assertEquals("", config.languages().get(0).statusChannel());
        assertEquals("", config.languages().get(1).statusChannel());
    }

    @Test
    void thePriceListInEnvExampleIsReadBackAsThreeTiers() throws Exception {
        final AccessSpec config =
                fromEnvironment(Map.of("NORDTAL_ACCESS_TIERS", envExampleValue("NORDTAL_ACCESS_TIERS")));

        assertEquals(3, config.tiers().size());
        assertEquals(30, config.tiers().get(0).days());
        assertEquals(700, config.tiers().get(2).priceCents());
    }

    @Test
    void aReplaceMeIdIsRefusedByNameRatherThanStartedWith() throws Exception {
        // REPLACE_ME over a row of zeros: zeros are a valid snowflake for a guild that does not exist.
        Files.writeString(directory.resolve("access.yml"), access().replace("access: '10'", "access: 'REPLACE_ME'"));

        final ConfigValidationException thrown = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(
                thrown.getMessage().contains("roles.access"),
                "the message has to name the setting, was: " + thrown.getMessage());
    }

    /**
     * The {@code @Protected} annotation and the bot's own startup rule have to name the same language.
     *
     * There is no way to notice at runtime if they stop doing so - the removal refusal lives in steward-worker, in
     * another process, and the startup check here would simply go on protecting a different tag without complaining.
     *
     * This is that check, at build time. It replaces reading the annotation reflectively into
     * {@code Configs.FALLBACK_LANGUAGE}: both the field and the annotation already name {@link Languages#FALLBACK_TAG},
     * so the reflection guarded against nothing the compiler does not, while adding one way for this class to fail to
     * initialise at all.
     */
    @Test
    void theLanguageStewardWorkerRefusesToRemoveIsTheOneThisBotFallsBackTo() throws Exception {
        final Method languages = AccessSpec.class.getMethod("languages");
        final Protected annotation = languages.getAnnotation(Protected.class);
        assertNotNull(
                annotation,
                "AccessSpec#languages() must carry @Protected - without it"
                        + " steward-worker lets an operator remove the fallback language through the API,"
                        + " and the bot only notices on its next restart");
        assertEquals("tag", annotation.field(), "@Protected has to match on the element's own tag field");
        assertEquals(
                Languages.FALLBACK_TAG,
                annotation.value(),
                "the protected tag and the fallback tag are the same language or the rule protects"
                        + " the wrong entry");
    }

    /**
     * Loads {@code access.yml} with a fake environment on top, the way the container's is.
     *
     * {@link Configs#access()} reads {@link System#getenv} and a test cannot set that, so this goes through
     * {@link ConfigLoader} directly with the same prefix. It therefore covers the overlay and not the validator - which
     * is the half at risk here: a JSON shape that does not map onto the spec fails inside Gson, long before any rule of
     * ours runs.
     */
    private AccessSpec fromEnvironment(final Map<String, String> environment) throws Exception {
        return handleFromEnvironment(environment).get();
    }

    /**
     * The handle itself: {@link #fromEnvironment} only needs the loaded spec.
     *
     * {@link ConfigHandle#environmentOverrides()} is what feeds {@code EnvOverrideFile.write} in
     * {@code Configs#load} - see
     * {@link #languagesOverriddenExactlyTheWayDevEnvExampleOverridesItEndsUpInTheMarkerFile()} below,
     * which needs the handle rather than the spec.
     */
    private ConfigHandle<AccessSpec> handleFromEnvironment(final Map<String, String> environment) throws Exception {
        Files.writeString(directory.resolve("access.yml"), access());
        return ConfigLoader.builder(directory.resolve("access.yml"), AccessSpec.class)
                .envPrefix("NORDTAL_ACCESS")
                .environment(environment::get)
                .load();
    }

    /**
     * The running bot's startup line has to name every setting {@code deploy/dev.env.example} overrides.
     *
     * This runs that same override, {@code NORDTAL_ACCESS_LANGUAGES}, through
     * {@link ConfigHandle#environmentOverrides()} and then
     * {@link EnvOverrideFile}, the whole path {@code Configs#load}'s {@code recordEnvironmentOverrides} step takes
     * in production - a private method a test cannot call directly, so this exercises the same two calls in the
     * same order instead of trusting that they are wired up.
     */
    @Test
    void languagesOverriddenExactlyTheWayDevEnvExampleOverridesItEndsUpInTheMarkerFile() throws Exception {
        final ConfigHandle<AccessSpec> handle =
                handleFromEnvironment(Map.of("NORDTAL_ACCESS_LANGUAGES", envExampleValue("NORDTAL_ACCESS_LANGUAGES")));

        assertTrue(
                handle.environmentOverrides().contains("languages"),
                "jcore itself has to report the override before anything downstream can - reported: "
                        + handle.environmentOverrides());

        EnvOverrideFile.write(handle.file(), handle.environmentOverrides());

        assertEquals(
                Optional.of(handle.environmentOverrides()),
                EnvOverrideFile.read(handle.file()),
                "the marker file steward-worker reads has to carry exactly what jcore reported");
    }

    /**
     * The other test above deliberately builds its own {@link ConfigHandle} and calls {@link EnvOverrideFile} itself.
     *
     * {@link Configs#load} is private and {@code Configs.access()} does not let a test inject environment
     * variables - so nothing above actually calls {@code Configs} 's own {@code recordEnvironmentOverrides} step.
     *
     * This is the test that does: it goes through the real, public entry point with no override in play at all,
     * and the only thing it can require is that the entry point writes some marker file - the empty-list case
     * {@code EnvOverrideFileTest} already covers for {@link EnvOverrideFile} on its own. A regression that deletes
     * the {@code recordEnvironmentOverrides(handle);} line from {@code Configs#load} shows up here as a missing
     * file, not as a wrong value in one.
     */
    @Test
    void loadingAccessYmlThroughConfigsAccessItselfLeavesAMarkerFileBesideIt() throws Exception {
        Files.writeString(directory.resolve("access.yml"), access());

        Configs.access();

        assertEquals(
                Optional.of(List.of()),
                EnvOverrideFile.read(directory.resolve("access.yml")),
                "nothing is overridden here, but Configs#load still has to run the write step - an"
                        + " absent marker file and an empty one are different facts");
    }
}
