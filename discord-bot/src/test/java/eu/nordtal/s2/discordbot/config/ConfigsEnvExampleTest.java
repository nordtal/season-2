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

    /**
     * Returns the value of {@code key} in the repository's {@code .env.example}, with a leading {@code #} stripped.
     *
     * Read from the real file, which {@code repositoryRootTestInputs} declares as an input of this test task.
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

    /** Returns the directory holding {@code settings.gradle.kts}, not the nearest {@code .env.example}. */
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
        // status-channel is empty rather than REPLACE_ME, since an operator may leave it out.
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
        // REPLACE_ME rather than zeros: zeros are a valid snowflake for a guild that does not exist.
        Files.writeString(directory.resolve("access.yml"), access().replace("access: '10'", "access: 'REPLACE_ME'"));

        final ConfigValidationException thrown = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(
                thrown.getMessage().contains("roles.access"),
                "the message has to name the setting, was: " + thrown.getMessage());
    }

    /**
     * The {@code @Protected} annotation and the bot's startup rule name the same fallback language.
     *
     * Nothing notices at runtime if they drift, because the removal refusal lives in steward-worker.
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
     * Loads {@code access.yml} with a fake environment on top, the way the container does.
     *
     * This covers the overlay, not the validator, since {@link Configs#access()} reads the real environment.
     */
    private AccessSpec fromEnvironment(final Map<String, String> environment) throws Exception {
        return handleFromEnvironment(environment).get();
    }

    /** Returns the loaded handle rather than the spec, for tests that need its environment overrides. */
    private ConfigHandle<AccessSpec> handleFromEnvironment(final Map<String, String> environment) throws Exception {
        Files.writeString(directory.resolve("access.yml"), access());
        return ConfigLoader.builder(directory.resolve("access.yml"), AccessSpec.class)
                .envPrefix("NORDTAL_ACCESS")
                .environment(environment::get)
                .load();
    }

    /**
     * Every setting {@code deploy/dev.env.example} overrides reaches the override marker file.
     *
     * Runs the same two calls as {@code Configs#load}, which is private.
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

    /** The public entry point writes an override marker file even with no override in play. */
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
