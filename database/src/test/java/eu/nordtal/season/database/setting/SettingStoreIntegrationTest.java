package eu.nordtal.season.database.setting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Stores settings against PostgreSQL and the real migrations. */
class SettingStoreIntegrationTest {

    private static final String SCHEMA = "{\"kind\":\"MAP\"}";

    private static final Actor ADMIN = Actor.person(DiscordId.of("123456789012345678"));

    @Test
    void aPublishedGroupReadsBackAndAPublishAgainReplacesItAndClearsTheProblem() {
        final SettingStore store = SettingStore.using(TestDatabase.fresh().dataSource());
        store.publish("smp", "distances", SCHEMA, "{\"view-distance\":32}", List.of(), true);
        store.problem("smp", "distances", "view-distance must be positive");
        store.publish("smp", "distances", SCHEMA, "{\"view-distance\":16}", List.of("view-distance"), false);

        final SettingStore.Group group = store.group("smp", "distances").orElseThrow();
        assertEquals("{\"view-distance\": 16}", group.defaults());
        assertEquals(List.of("view-distance"), group.environment());
        assertFalse(group.live());
        assertEquals(null, group.problem());
    }

    @Test
    void aChangeSetsAndRemovesValuesAndAStaleOneWritesNothing() {
        final SettingStore store = SettingStore.using(TestDatabase.fresh().dataSource());
        store.publish("network", "network", SCHEMA, "{}", List.of(), true);

        assertTrue(store.change(
                "network",
                "network",
                Map.of("max-players", "50", "motd.smp", "\"hi\""),
                ADMIN,
                held -> held.isEmpty()));
        final Map<String, @Nullable String> removal = new HashMap<>();
        removal.put("motd.smp", null);
        assertTrue(store.change("network", "network", removal, ADMIN, held -> held.size() == 2));
        assertFalse(
                store.change("network", "network", Map.of("max-players", "60"), ADMIN, held -> false),
                "the predicate saw the rows and said they had moved on");

        assertEquals(
                List.of(new SettingStore.Value("network", "network", "max-players", "50")),
                store.overrides(Set.of("network", "smp")));
    }

    @Test
    void aValueMayPrecedeItsGroupsFirstPublication() {
        final SettingStore store = SettingStore.using(TestDatabase.fresh().dataSource());

        // The update run sets the pack of the proxy before a proxy of this season has started.
        assertTrue(store.change("proxy", "pack", Map.of("sha1", "\"ab12\""), Actor.STEWARD, held -> true));
        store.publish("proxy", "pack", SCHEMA, "{}", List.of(), false);

        assertEquals(
                List.of(new SettingStore.Value("proxy", "pack", "sha1", "\"ab12\"")), store.overrides(Set.of("proxy")));
    }

    @Test
    void aServicePublishesButOnlyStewardChangesAValue() {
        final TestDatabase database = TestDatabase.fresh();
        final SettingStore smp = SettingStore.using(database.dataSourceAs(DatabaseRole.SMP));
        smp.publish("smp", "milestones", SCHEMA, "{}", List.of(), true);

        assertThrows(
                UnableToExecuteStatementException.class,
                () -> smp.change("smp", "milestones", Map.of("track", "[1]"), Actor.HOST, held -> true));
        assertTrue(SettingStore.using(database.dataSourceAs(DatabaseRole.STEWARD))
                .change("smp", "milestones", Map.of("track", "[1]"), ADMIN, held -> true));
        assertEquals(
                List.of(new SettingStore.Value("smp", "milestones", "track", "[1]")),
                SettingStore.using(database.dataSourceAs(DatabaseRole.PROXY)).overrides(Set.of("smp")));
    }

    @Test
    void aListOfObjectsIsStoredAsOneRowPerValueAndReadsBackWhole() {
        final TestDatabase database = TestDatabase.fresh();
        final SettingStore store = SettingStore.using(database.dataSource());
        final String languages = "[{\"tag\": \"en\", \"live\": true, \"items\": [], \"weight\": 1.50, \"note\": null},"
                + " {\"tag\": \"de\", \"live\": false, \"items\": [\"A\", \"B\"], \"weight\": 30, \"note\": {}}]";

        assertTrue(store.change("discord-bot", "access", Map.of("languages", languages), ADMIN, held -> true));

        assertEquals(
                List.of(new SettingStore.Value(
                        "discord-bot",
                        "access",
                        "languages",
                        "[{\"tag\": \"en\", \"live\": true, \"note\": null, \"items\": [], \"weight\": 1.50},"
                                + " {\"tag\": \"de\", \"live\": false, \"note\": {}, \"items\": [\"A\", \"B\"],"
                                + " \"weight\": 30}]")),
                store.overrides(Set.of("discord-bot")));
        // The list, two objects, ten fields and two entries of the second list.
        final int rows = Jdbi.create(database.dataSource())
                .withHandle(handle -> handle.createQuery("SELECT count(*) FROM setting_override_node")
                        .mapTo(Integer.class)
                        .one());
        assertEquals(15, rows);
    }
}
