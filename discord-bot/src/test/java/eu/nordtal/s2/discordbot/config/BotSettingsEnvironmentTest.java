package eu.nordtal.s2.discordbot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.spec.annotation.Protected;
import eu.nordtal.s2.settings.EnvOverrideFile;
import eu.nordtal.s2.settings.SettingsException;
import java.lang.reflect.Method;
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
 * How {@code access.yml}'s settings come through the environment overlay and into the override marker file.
 */
class BotSettingsEnvironmentTest {

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
        System.setProperty(BotSettings.DIRECTORY_PROPERTY, directory.toString());
    }

    @AfterEach
    void restore() {
        System.clearProperty(BotSettings.DIRECTORY_PROPERTY);
    }

    @Test
    void aReplaceMeIdIsRefusedByNameRatherThanStartedWith() throws Exception {
        // REPLACE_ME rather than zeros: zeros are a valid snowflake for a guild that does not exist.
        Files.writeString(directory.resolve("access.yml"), access().replace("access: '10'", "access: 'REPLACE_ME'"));

        final SettingsException thrown = assertThrows(SettingsException.class, BotSettings::access);
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

    /** Loads {@code access.yml} with a fake environment on top, the way the container does. */
    private ConfigHandle<AccessSpec> handleFromEnvironment(final Map<String, String> environment) throws Exception {
        Files.writeString(directory.resolve("access.yml"), access());
        return ConfigLoader.builder(directory.resolve("access.yml"), AccessSpec.class)
                .envPrefix("NORDTAL_ACCESS")
                .environment(environment::get)
                .load();
    }

    /**
     * A setting the environment overrides reaches the override marker file.
     *
     * Runs the same two calls as {@code FileSettings#load}.
     */
    @Test
    void anOverriddenSettingEndsUpInTheMarkerFile() throws Exception {
        final ConfigHandle<AccessSpec> handle = handleFromEnvironment(Map.of("NORDTAL_ACCESS_GUILD_ID", "2"));

        assertTrue(
                handle.environmentOverrides().contains("guild-id"),
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

        BotSettings.access();

        assertEquals(
                Optional.of(List.of()),
                EnvOverrideFile.read(directory.resolve("access.yml")),
                "nothing is overridden here, but FileSettings#load still has to run the write step - an"
                        + " absent marker file and an empty one are different facts");
    }
}
