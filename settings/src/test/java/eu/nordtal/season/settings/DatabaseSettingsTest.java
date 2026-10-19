package eu.nordtal.season.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.inbox.SettingsChange;
import eu.nordtal.season.database.setting.SettingStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Checks that a group takes the spec's defaults, then the admin's values, then the environment, and is published. */
class DatabaseSettingsTest {

    private static final Group<ExampleSpec> EXAMPLE = Group.of("example", ExampleSpec.class)
            .checkedBy(values -> {
                if (values.viewDistance() > 32) {
                    throw new IllegalArgumentException("view-distance is above 32");
                }
            })
            .whileRunning();

    private final MemorySettingStore store = new MemorySettingStore();

    @Test
    void nothingStoredIsTheSpecsDefaultsAndTheGroupIsPublished() throws Exception {
        final ExampleSpec values = store.settings("smp").load(EXAMPLE).get();

        assertEquals(0, values.viewDistance());
        assertEquals("nordtal", values.name());
        final SettingStore.Group published = store.group("smp", "example").orElseThrow();
        assertTrue(published.schema().contains("\"view-distance\""), published.schema());
        assertTrue(published.live());
        assertEquals(List.of(), published.environment());
        assertNull(published.problem());
    }

    @Test
    void aStoredValueIsTakenAndListsAndNestedGroupsToo() throws Exception {
        store.set("smp", "example", "view-distance", 12).set("smp", "example", "nested.words", List.of("x", "y"));

        final ExampleSpec values = store.settings("smp").load(EXAMPLE).get();

        assertEquals(12, values.viewDistance());
        assertEquals(List.of("x", "y"), values.nested().words());
    }

    @Test
    void aProcessDefaultIsWhatItRunsWithAndWhatStewardIsShown() throws Exception {
        final Group<ExampleSpec> distances = EXAMPLE.defaulting("view-distance", 32);

        assertEquals(32, store.settings("smp").load(distances).get().viewDistance());
        assertTrue(store.group("smp", "example").orElseThrow().defaults().contains("\"view-distance\":32"));
        store.set("smp", "example", "view-distance", 12);
        assertEquals(12, store.settings("smp").load(distances).get().viewDistance());
    }

    @Test
    void theEnvironmentWinsAndOnlyItsPathIsPublished() throws Exception {
        final Map<String, String> variables = Map.of("NORDTAL_TEST_EXAMPLE_NAME", "from-the-host");
        store.set("smp", "example", "name", "from-steward");

        final ExampleSpec values = store.settings(
                        "smp", Environment.of("NORDTAL_TEST").reading(variables::get))
                .load(EXAMPLE)
                .get();

        assertEquals("from-the-host", values.name());
        final SettingStore.Group published = store.group("smp", "example").orElseThrow();
        assertEquals(List.of("name"), published.environment());
        assertFalse(published.defaults().contains("from-the-host"), "an environment value is never published");
    }

    @Test
    void aTrialReadsTheChangeOverTheStoredRowsAndTheCheckRefusesItWithoutSavingIt() throws Exception {
        store.set("smp", "example", "view-distance", 12).set("smp", "example", "name", "stored");
        final DatabaseSettings settings = store.settings("smp");

        final ExampleSpec tried =
                settings.trial(EXAMPLE, new SettingsChange("example", Map.of("view-distance", "20"), List.of("name")));

        assertEquals(20, tried.viewDistance());
        assertEquals("nordtal", tried.name(), "a reset path is back at its default");
        final SettingsException refused = assertThrows(
                SettingsException.class,
                () -> settings.trial(EXAMPLE, new SettingsChange("example", Map.of("view-distance", "99"), List.of())));
        assertTrue(refused.getMessage().contains("view-distance is above 32"), refused.getMessage());
        assertEquals(12, settings.load(EXAMPLE).get().viewDistance(), "a trial stores nothing");
    }

    @Test
    void aStoredValueTheCheckRefusesLeavesTheDefaultsAndSaysWhy() throws Exception {
        store.set("smp", "example", "view-distance", 99).set("smp", "example", "name", "kept-out-too");

        final ExampleSpec values = store.settings("smp").load(EXAMPLE).get();

        assertEquals(0, values.viewDistance());
        assertEquals("nordtal", values.name(), "a refused group is refused as a whole");
        final String problem = store.group("smp", "example").orElseThrow().problem();
        assertTrue(problem != null && problem.contains("view-distance"), String.valueOf(problem));
    }

    @Test
    void aRefusedReloadKeepsTheValuesInUseAndTheNextGoodOneClearsTheProblem() throws Exception {
        final Setting<ExampleSpec> example = store.settings("smp").load(EXAMPLE);
        store.set("smp", "example", "view-distance", 99);

        assertThrows(SettingsException.class, example::reload);
        assertEquals(0, example.get().viewDistance());
        assertTrue(store.group("smp", "example").orElseThrow().problem() != null);

        store.set("smp", "example", "view-distance", 16);
        example.reload();
        assertEquals(16, example.get().viewDistance());
        assertNull(store.group("smp", "example").orElseThrow().problem());
    }

    @Test
    void theInstanceHandedOutReadsThroughToEveryReload() throws Exception {
        final Setting<ExampleSpec> example = store.settings("smp").load(EXAMPLE);
        final ExampleSpec held = example.get();
        store.set("smp", "example", "view-distance", 12);

        example.reload();

        assertEquals(12, held.viewDistance());
    }

    @Test
    void aReloadSaysWhetherTheValuesChanged() throws Exception {
        final Setting<ExampleSpec> example = store.settings("smp").load(EXAMPLE);

        assertFalse(example.reload(), "nothing stored changed");
        store.set("smp", "example", "view-distance", 12);
        assertTrue(example.reload());
        assertFalse(example.reload(), "the same row again is no change");
    }

    @Test
    void aStoredSecretOrAPathTheSpecLacksIsIgnored() throws Exception {
        store.set("smp", "example", "token", "leaked").set("smp", "example", "retired", 5);

        final ExampleSpec values = store.settings("smp").load(EXAMPLE).get();

        assertEquals("", values.token());
    }

    @Test
    void aGroupThatAppliesAtTheNextStartIsNeverReloaded() throws Exception {
        final Setting<ExampleSpec> atStart = store.settings("smp").load(Group.of("example", ExampleSpec.class));

        assertThrows(IllegalStateException.class, atStart::reload);
        assertFalse(store.group("smp", "example").orElseThrow().live());
    }

    @Test
    void aNetworkWideGroupIsSharedAndTheEnvironmentLeavesItAlone() throws Exception {
        final Map<String, String> variables = Map.of("NORDTAL_TEST_EXAMPLE_NAME", "from-the-host");
        store.set(SettingStore.NETWORK, "example", "name", "everywhere");

        final ExampleSpec values = store.settings(
                        "smp", Environment.of("NORDTAL_TEST").reading(variables::get))
                .load(EXAMPLE.networkWide())
                .get();

        assertEquals("everywhere", values.name());
        assertTrue(store.group(SettingStore.NETWORK, "example").isPresent());
        assertTrue(store.group("smp", "example").isEmpty());
    }

    @Test
    void theEnvironmentAloneServesTheBootstrap() throws Exception {
        final Map<String, String> variables = Map.of("NORDTAL_TEST_EXAMPLE_VIEW_DISTANCE", "7");

        final ExampleSpec values = EnvironmentSettings.of(
                        Environment.of("NORDTAL_TEST").reading(variables::get))
                .load(EXAMPLE.defaulting("name", "bootstrap"))
                .get();

        assertEquals(7, values.viewDistance());
        assertEquals("bootstrap", values.name());
    }
}
