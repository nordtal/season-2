package eu.nordtal.season.database.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.messages.MessageOverride;
import eu.nordtal.season.messages.PackagedTexts;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.Test;

/** Stores message overrides against a real PostgreSQL running the real migrations; skipped without Docker. */
class MessageOverrideStoreIntegrationTest {

    private static final Actor ADMIN = Actor.person(DiscordId.of("123456789012345678"));

    @Test
    void stewardWritesTheRowsAndEveryServerReadsThoseOfItsBundles() {
        final TestDatabase database = TestDatabase.fresh();
        final MessageOverrideStore steward = MessageOverrideStore.using(database.dataSourceAs(DatabaseRole.STEWARD));
        final String replaced = PackagedTexts.hash(List.of("Hallo {name}!"));
        steward.change("smp", "tab.footer", "de", List.of("Moin", "Servus"), List.of("Hallo {name}!"), ADMIN);
        steward.change("proxy", "motd", "en", List.of("Hi"), List.of(), Actor.STEWARD);

        assertEquals(
                List.of(
                        new MessageOverride("smp", "tab.footer", "de", 0, "Moin", replaced),
                        new MessageOverride("smp", "tab.footer", "de", 1, "Servus", replaced)),
                MessageOverrideStore.using(database.dataSourceAs(DatabaseRole.SMP))
                        .overrides(Set.of("smp", "paper-common")));
    }

    @Test
    void anOverrideKeepsThePackagedTextsItReplacedForStewardToShowOnceTheyChange() {
        final MessageOverrideStore steward =
                MessageOverrideStore.using(TestDatabase.fresh().dataSourceAs(DatabaseRole.STEWARD));
        steward.change("smp", "tab.footer", "de", List.of("Moin", "Servus"), List.of("Hallo", "Hi {name}"), ADMIN);
        steward.change("proxy", "motd", "de", List.of("Moin"), List.of(), ADMIN);

        assertEquals(
                Map.of("smp/tab.footer/de", List.of("Hallo", "Hi {name}")),
                steward.originals(Set.of("smp", "proxy")),
                "one entry per key and language, not per variant, and none where nothing was replaced");
    }

    @Test
    void aChangeReplacesTheVariantsAsOneSetAndNoneRemovesTheOverride() {
        final MessageOverrideStore store =
                MessageOverrideStore.using(TestDatabase.fresh().dataSource());
        store.change("limbo", "waiting", "en", List.of("a", "b", "c"), List.of(), ADMIN);
        store.change("limbo", "waiting", "en", List.of("d"), List.of(), ADMIN);
        store.change("limbo", "waiting", "de", List.of("e"), List.of(), ADMIN);
        store.change("limbo", "waiting", "de", List.of(), List.of(), ADMIN);

        assertEquals(
                List.of(new MessageOverride("limbo", "waiting", "en", 0, "d", null)), store.overrides(Set.of("limbo")));
    }

    @Test
    void aServerCannotWriteAnOverride() {
        final TestDatabase database = TestDatabase.fresh();

        assertThrows(
                UnableToExecuteStatementException.class,
                () -> MessageOverrideStore.using(database.dataSourceAs(DatabaseRole.SMP))
                        .change("smp", "tab.footer", "de", List.of("Moin"), List.of(), Actor.HOST));
    }
}
