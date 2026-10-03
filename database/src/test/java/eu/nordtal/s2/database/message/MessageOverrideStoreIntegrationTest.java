package eu.nordtal.s2.database.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.DatabaseRole;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.messages.MessageOverride;
import eu.nordtal.s2.messages.PackagedTexts;
import java.util.List;
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
        steward.change("smp", "tab.footer", "de", List.of("Moin", "Servus"), replaced, ADMIN);
        steward.change("proxy", "motd", "en", List.of("Hi"), null, Actor.STEWARD);

        assertEquals(
                List.of(
                        new MessageOverride("smp", "tab.footer", "de", 0, "Moin", replaced),
                        new MessageOverride("smp", "tab.footer", "de", 1, "Servus", replaced)),
                MessageOverrideStore.using(database.dataSourceAs(DatabaseRole.SMP))
                        .overrides(Set.of("smp", "paper-common")));
    }

    @Test
    void aChangeReplacesTheVariantsAsOneSetAndNoneRemovesTheOverride() {
        final MessageOverrideStore store =
                MessageOverrideStore.using(TestDatabase.fresh().dataSource());
        store.change("limbo", "waiting", "en", List.of("a", "b", "c"), null, ADMIN);
        store.change("limbo", "waiting", "en", List.of("d"), null, ADMIN);
        store.change("limbo", "waiting", "de", List.of("e"), null, ADMIN);
        store.change("limbo", "waiting", "de", List.of(), null, ADMIN);

        assertEquals(
                List.of(new MessageOverride("limbo", "waiting", "en", 0, "d", null)), store.overrides(Set.of("limbo")));
    }

    @Test
    void aServerCannotWriteAnOverride() {
        final TestDatabase database = TestDatabase.fresh();

        assertThrows(
                UnableToExecuteStatementException.class,
                () -> MessageOverrideStore.using(database.dataSourceAs(DatabaseRole.SMP))
                        .change("smp", "tab.footer", "de", List.of("Moin"), null, Actor.HOST));
    }
}
