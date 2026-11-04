package eu.nordtal.season.database.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseMessages;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.setting.SettingRule;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/** Holds that a settings refusal an older server wrote in words reads back as its group's refusal after the upgrade. */
class SettingsRefusalNamesItsGroupUpgradeIntegrationTest {

    @Test
    void aRefusalInWordsNamesTheGroupOfItsRequestWithTheRuleValid() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "110");
        final Inbox<SmpRequest> smp = Inbox.over(upgraded.dataSource(), SmpRequest.TABLE);
        final long inWords = smp.submit(
                        new SmpRequest.CheckSettings(new SettingsChange("border", Map.of(), List.of())),
                        Actor.person(DiscordId.of("400000000000000001")))
                .id();
        assertTrue(smp.claim().isPresent());
        smp.settle(
                inWords,
                Outcome.refused(ServerRefusal.SETTINGS_REFUSED.with("smp", "border", SettingRule.VALID, null)));
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> handle.execute(
                "UPDATE smp_refusal SET setting = NULL, rule = NULL,"
                        + " detail = 'border: nether-border-diameter must be greater than zero, was 0'"
                        + " WHERE request = ?",
                inWords));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "111");

        assertEquals(
                DatabaseMessages.MESSAGES.server().settingsRefused("smp", "border", SettingRule.VALID, null),
                smp.find(inWords).orElseThrow().refusal().orElseThrow().message(),
                "the refusal names the group it was asked for and the rule VALID");
    }
}
