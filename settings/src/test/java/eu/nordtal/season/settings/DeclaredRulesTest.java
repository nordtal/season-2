package eu.nordtal.season.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import eu.nordtal.season.database.inbox.SettingsChange;
import eu.nordtal.season.database.setting.SettingRule;
import eu.nordtal.season.settings.rule.Between;
import eu.nordtal.season.settings.rule.Positive;
import eu.nordtal.season.settings.rule.Required;
import eu.nordtal.season.settings.rule.Unique;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Checks that the rules a spec declares reach its schema and refuse a value before the group's own check. */
class DeclaredRulesTest {

    private static final Group<Ruled> RULED = Group.of("ruled", Ruled.class).checkedBy(values -> {
        throw new IllegalStateException("the group's own check runs only after the declared rules");
    });

    private final MemorySettingStore store = new MemorySettingStore();

    @Test
    void theAnnotationsOfAGetterAreTheRulesOfItsNodeInTheOrderOfTheRules() {
        final JsonObject schema = SpecJson.schemaTree(Ruled.class);
        final JsonObject children = schema.getAsJsonObject("children");

        assertEquals(
                "[{\"rule\":\"POSITIVE\"},{\"rule\":\"AT_LEAST\",\"limit\":2},{\"rule\":\"AT_MOST\",\"limit\":32}]",
                children.getAsJsonObject("distance").get("rules").toString());
        assertEquals(
                "[{\"rule\":\"UNIQUE\",\"field\":\"name\"}]",
                children.getAsJsonObject("kits").get("rules").toString());
        assertEquals(
                "[{\"rule\":\"REQUIRED\"}]",
                children.getAsJsonObject("kits")
                        .getAsJsonObject("children")
                        .getAsJsonObject("name")
                        .get("rules")
                        .toString());
    }

    @Test
    void aTrialIsRefusedByTheDeclaredRuleBeforeTheGroupsCheck() {
        final SettingsChange far = new SettingsChange("ruled", Map.of("distance", "99"), List.of());

        final SettingRefused refused = SettingRefused.of(
                assertThrows(
                        SettingsException.class, () -> store.settings("smp").trial(RULED, far)),
                "ruled");

        assertEquals(
                List.of("distance", SettingRule.AT_MOST, "32", "distance must be at most 32, was 99"),
                List.of(refused.setting(), refused.rule(), String.valueOf(refused.value()), refused.getMessage()));
    }

    @Test
    void aGroupFromTheEnvironmentIsCheckedByItsRulesToo() {
        final Environment environment =
                Environment.of("NORDTAL").withMain("ruled").reading(Map.of("NORDTAL_DISTANCE", "1")::get);

        final SettingRefused refused = SettingRefused.of(
                assertThrows(
                        SettingsException.class,
                        () -> EnvironmentSettings.of(environment).load(Group.of("ruled", Ruled.class))),
                "ruled");

        assertEquals(SettingRule.AT_LEAST, refused.rule());
    }

    /** A group whose rules are declared. */
    @ConfigSpec
    public interface Ruled {

        @Key("distance")
        @Positive
        @Between(least = 2, most = 32)
        default int distance() {
            return 8;
        }

        @Key("kits")
        @Unique("name")
        default List<Kit> kits() {
            return List.of();
        }

        /** One entry of the list. */
        @ConfigSpec
        interface Kit {

            @Key("name")
            @Required
            default String name() {
                return "";
            }
        }
    }
}
