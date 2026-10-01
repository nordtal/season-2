package eu.nordtal.s2.database.setting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.DatabaseRole;
import eu.nordtal.s2.database.TestDatabase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Stores settings against a real PostgreSQL running the real migrations; skipped without Docker. */
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

        // The update run sets the proxy's pack before a proxy of this season has ever started.
        assertTrue(store.change("proxy", "pack", Map.of("sha1", "\"ab12\""), Actor.STEWARD, held -> true));
        store.publish("proxy", "pack", SCHEMA, "{}", List.of(), false);

        assertEquals(
                List.of(new SettingStore.Value("proxy", "pack", "sha1", "\"ab12\"")), store.overrides(Set.of("proxy")));
    }

    @Test
    void anImportNeverReplacesAnAdminsValue() {
        final SettingStore store = SettingStore.using(TestDatabase.fresh().dataSource());
        store.publish("proxy", "gate", SCHEMA, "{}", List.of(), false);
        store.change("proxy", "gate", Map.of("server-smp", "\"admin\""), ADMIN, held -> true);

        final List<String> written = store.importMissing(
                "proxy", "gate", Map.of("server-smp", "\"file\"", "server-limbo", "\"file\""), Actor.HOST);

        assertEquals(List.of("server-limbo"), written);
        assertEquals(
                List.of(
                        new SettingStore.Value("proxy", "gate", "server-limbo", "\"file\""),
                        new SettingStore.Value("proxy", "gate", "server-smp", "\"admin\"")),
                store.overrides(Set.of("proxy")));
    }

    @Test
    void aServicePublishesAndImportsButOnlyStewardChangesAValue() {
        final TestDatabase database = TestDatabase.fresh();
        final SettingStore smp = SettingStore.using(database.dataSourceAs(DatabaseRole.SMP));
        smp.publish("smp", "milestones", SCHEMA, "{}", List.of(), true);
        smp.importMissing("smp", "milestones", Map.of("track", "[]"), Actor.HOST);

        assertThrows(
                UnableToExecuteStatementException.class,
                () -> smp.change("smp", "milestones", Map.of("track", "[1]"), Actor.HOST, held -> true));
        assertTrue(SettingStore.using(database.dataSourceAs(DatabaseRole.STEWARD))
                .change("smp", "milestones", Map.of("track", "[1]"), ADMIN, held -> true));
        assertEquals(
                List.of(new SettingStore.Value("smp", "milestones", "track", "[1]")),
                SettingStore.using(database.dataSourceAs(DatabaseRole.PROXY)).overrides(Set.of("smp")));
    }
}
