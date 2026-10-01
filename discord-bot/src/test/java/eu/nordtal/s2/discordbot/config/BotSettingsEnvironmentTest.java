package eu.nordtal.s2.discordbot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.spec.annotation.Protected;
import eu.nordtal.s2.settings.MemorySettingStore;
import eu.nordtal.s2.settings.SettingsException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How the access settings are refused by name, and how the environment reaches them. */
class BotSettingsEnvironmentTest {

    private final MemorySettingStore store = new MemorySettingStore();

    /** A complete, valid access group, as Steward stores it. */
    private static Map<String, Object> access() {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("tiers", """
                [{"days": 30, "price-cents": 300},
                 {"days": 60, "price-cents": 500},
                 {"days": 90, "price-cents": 700}]""");
        values.put("languages", """
                [{"tag": "en", "role": "30", "contribution-channel": "31", "link-channel": "32",
                  "hunger-games-channel": "39"},
                 {"tag": "de", "role": "33", "contribution-channel": "34", "link-channel": "35",
                  "hunger-games-channel": "40"}]""");
        values.put("guild-id", "1");
        values.put("donation-cents", 500);
        values.put("roles.access", "10");
        values.put("roles.donor", "11");
        values.put("roles.admin", "14");
        values.put("roles.admin-ping", "15");
        values.put("channels.admin", "24");
        return values;
    }

    @Test
    void aCompleteAccessGroupLoads() throws Exception {
        assertEquals(
                List.of("en", "de"),
                store.checked(BotSettings.SERVICE, BotSettings.ACCESS, access()).languages().stream()
                        .map(AccessSpec.LanguageSpec::tag)
                        .toList());
    }

    @Test
    void aReplaceMeIdIsRefusedByNameRatherThanStartedWith() {
        // REPLACE_ME rather than zeros: zeros are a valid snowflake for a guild that does not exist.
        final Map<String, Object> values = access();
        values.put("roles.access", "REPLACE_ME");

        final SettingsException thrown = assertThrows(
                SettingsException.class, () -> store.checked(BotSettings.SERVICE, BotSettings.ACCESS, values));
        assertTrue(
                thrown.getMessage().contains("roles.access"),
                "the message has to name the setting, was: " + thrown.getMessage());
    }

    /**
     * The {@code @Protected} annotation and the bot's startup rule name the same fallback language.
     *
     * Nothing notices at runtime if they drift, because the removal refusal lives in steward.
     */
    @Test
    void theLanguageStewardRefusesToRemoveIsTheOneThisBotFallsBackTo() throws Exception {
        final Method languages = AccessSpec.class.getMethod("languages");
        final Protected annotation = languages.getAnnotation(Protected.class);
        assertNotNull(
                annotation,
                "AccessSpec#languages() must carry @Protected - without it"
                        + " steward lets an operator remove the fallback language through the API,"
                        + " and the bot only notices on its next restart");
        assertEquals("tag", annotation.field(), "@Protected has to match on the element's own tag field");
        assertEquals(
                Languages.FALLBACK_TAG,
                annotation.value(),
                "the protected tag and the fallback tag are the same language or the rule protects"
                        + " the wrong entry");
    }

    /** A value under {@code NORDTAL_ACCESS_} wins over what is stored, and Steward is told which path it holds. */
    @Test
    void theEnvironmentWinsAndStewardIsToldWhichPathItHolds() throws Exception {
        access().forEach((path, value) -> store.set(BotSettings.SERVICE, "access", path, value));

        final AccessSpec taken = BotSettings.access(store.settings(
                        BotSettings.SERVICE,
                        BotSettings.ENVIRONMENT.reading(Map.of("NORDTAL_ACCESS_GUILD_ID", "2")::get)))
                .get();

        assertEquals("2", taken.guildId());
        assertEquals(
                List.of("guild-id"),
                store.group(BotSettings.SERVICE, "access").orElseThrow().environment());
    }
}
