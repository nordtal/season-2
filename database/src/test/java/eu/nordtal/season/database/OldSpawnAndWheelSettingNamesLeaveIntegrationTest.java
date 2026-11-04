package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.database.inbox.SettingsChange;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.database.setting.SettingRule;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/** Holds that an admin's values under the spawn's and the wheel's old names leave, and nothing else does. */
class OldSpawnAndWheelSettingNamesLeaveIntegrationTest {

    @Test
    void theOldPathsLeaveWithTheirNodesAndEveryOtherValueStays() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "108");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO setting_override (service, name, path, actor_kind, changed) VALUES
                        ('smp', 'spawn', 'spawn-regions', 'HOST', now()),
                        ('smp', 'spawn', 'first-join-spawn', 'HOST', now()),
                        ('smp', 'spawn', 'first-join-spawn.x', 'HOST', now()),
                        ('smp', 'spawn', 'protected-areas', 'HOST', now()),
                        ('smp', 'spawn', 'nordtal-spawn.x', 'HOST', now()),
                        ('smp', 'wheel', 'wheel-regions', 'HOST', now()),
                        ('smp', 'wheel', 'wheel-blocks', 'HOST', now()),
                        ('smp', 'wheel', 'spawn-regions', 'HOST', now()),
                        ('hunger-games', 'spawn', 'spawn-regions', 'HOST', now())
                    """);
            handle.execute("""
                    INSERT INTO setting_override_node
                        (service, name, path, node, parent, field, ordinal, kind, string, number, flag)
                    SELECT o.service, o.name, o.path, n.node, n.parent, n.field, n.ordinal, n.kind, n.string,
                           n.number, n.flag
                    FROM setting_override AS o, setting_override_nodes('[{"world": "nordtal"}]'::jsonb) AS n
                    """);
        });

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "109");

        final List<String> kept = List.of(
                "hunger-games/spawn/spawn-regions",
                "smp/spawn/nordtal-spawn.x",
                "smp/spawn/protected-areas",
                "smp/wheel/spawn-regions",
                "smp/wheel/wheel-blocks");
        assertEquals(
                kept,
                jdbi.withHandle(handle -> handle.createQuery(
                                "SELECT service || '/' || name || '/' || path FROM setting_override ORDER BY 1")
                        .mapTo(String.class)
                        .list()),
                "only smp's spawn and wheel values under the old names leave");
        assertEquals(
                kept,
                jdbi.withHandle(
                        handle -> handle.createQuery("""
                                SELECT DISTINCT service || '/' || name || '/' || path FROM setting_override_node
                                ORDER BY 1""").mapTo(String.class).list()),
                "their nodes leave with them");
    }

    @Test
    void aCornersRefusalReadsAsValidAndEveryRuleLeftIsStillKept() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "108");
        final Inbox<SmpRequest> smp = Inbox.over(upgraded.dataSource(), SmpRequest.TABLE);
        final long refused = smp.submit(
                        new SmpRequest.CheckSettings(new SettingsChange("spawn", Map.of(), List.of())),
                        Actor.person(DiscordId.of("400000000000000001")))
                .id();
        assertTrue(smp.claim().isPresent());
        smp.settle(
                refused,
                Outcome.refused(
                        ServerRefusal.SETTINGS_REFUSED.with("smp", "protected-areas", SettingRule.VALID, null)));
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> handle.execute("UPDATE smp_refusal SET rule = 'CORNERS' WHERE request = ?", refused));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "109");

        assertEquals(
                ServerRefusal.SETTINGS_REFUSED
                        .with("smp", "protected-areas", SettingRule.VALID, null)
                        .message(),
                smp.find(refused).orElseThrow().refusal().orElseThrow().message(),
                "an old CORNERS refusal reads back as VALID");
        for (final SettingRule rule : SettingRule.values()) {
            jdbi.useHandle(handle ->
                    handle.execute("UPDATE smp_refusal SET rule = ? WHERE request = ?", rule.name(), refused));
        }
        assertEquals(
                List.of(1L),
                jdbi.withHandle(
                        handle -> handle.createQuery("""
                                SELECT count(DISTINCT pg_get_constraintdef(oid)) FROM pg_constraint
                                WHERE conname IN ('smp_refusal_rule_check', 'hunger_games_refusal_rule_check')""").mapTo(Long.class).list()),
                "every rule left is kept, and both refusal tables check the same rules");
    }
}
