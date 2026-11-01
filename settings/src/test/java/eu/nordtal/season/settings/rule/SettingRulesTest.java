package eu.nordtal.season.settings.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.database.setting.SettingRule;
import eu.nordtal.season.settings.SettingRefused;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Runs the shared cases of the declared rules, which Steward's form runs too, so both evaluators agree. */
class SettingRulesTest {

    @TestFactory
    Stream<DynamicTest> everySharedCaseFindsItsBreaches() throws IOException {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final JsonElement element : cases()) {
            final JsonObject one = element.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest(
                    one.get("case").getAsString(),
                    () -> assertEquals(
                            expected(one.getAsJsonArray("breaches")),
                            SettingRules.breaches(
                                    one.getAsJsonObject("schema"), one.getAsJsonObject("values"), skipped(one)))));
        }
        return tests.stream();
    }

    @Test
    void theFirstBreachRefusesWithTheEntryInTheSentence() {
        final JsonObject schema = JsonParser.parseString(
                        "{\"kind\":\"MAP\",\"children\":{\"tiers\":{\"kind\":\"LIST\",\"children\":"
                                + "{\"days\":{\"kind\":\"SCALAR\",\"rules\":[{\"rule\":\"POSITIVE\"}]}}}}}")
                .getAsJsonObject();
        final JsonObject values = JsonParser.parseString("{\"tiers\":[{\"days\":3},{\"days\":0},{\"days\":-1}]}")
                .getAsJsonObject();

        final SettingRefused refused = assertThrows(SettingRefused.class, () -> SettingRules.check(schema, values));

        assertEquals(
                List.of("tiers.days", SettingRule.POSITIVE, "tiers[1].days must be greater than zero, was 0"),
                List.of(refused.setting(), refused.rule(), String.valueOf(refused.getMessage())));
    }

    private static JsonArray cases() throws IOException {
        try (InputStream stream = Objects.requireNonNull(
                        SettingRulesTest.class.getResourceAsStream("rule-cases.json"), "rule-cases.json");
                Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonArray();
        }
    }

    private static Set<String> skipped(final JsonObject one) {
        final Set<String> skipped = new HashSet<>();
        if (one.get("skipped") instanceof final JsonArray paths) {
            paths.forEach(path -> skipped.add(path.getAsString()));
        }
        return skipped;
    }

    private static List<SettingRules.Breach> expected(final JsonArray breaches) {
        final List<SettingRules.Breach> expected = new ArrayList<>();
        for (final JsonElement element : breaches) {
            final JsonObject breach = element.getAsJsonObject();
            final List<Integer> entries = new ArrayList<>();
            breach.getAsJsonArray("entries").forEach(index -> entries.add(index.getAsInt()));
            expected.add(new SettingRules.Breach(
                    breach.get("setting").getAsString(),
                    entries,
                    SettingRule.valueOf(breach.get("rule").getAsString()),
                    breach.get("value").isJsonNull()
                            ? null
                            : breach.get("value").getAsString()));
        }
        return expected;
    }
}
