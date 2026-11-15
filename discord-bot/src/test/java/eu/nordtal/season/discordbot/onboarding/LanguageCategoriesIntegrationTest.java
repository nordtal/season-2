package eu.nordtal.season.discordbot.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.TestDatabase;
import java.util.List;
import org.junit.jupiter.api.Test;

class LanguageCategoriesIntegrationTest {
    @Test
    void anInterruptedCategoryChangeKeepsBothCategoriesUntilTheirDenialsAreRemoved() {
        final var database = TestDatabase.fresh();
        final var before = new LanguageCategories(Jdbis.over(database.dataSourceAs(DatabaseRole.DISCORD_BOT)));
        final var old = new LanguageCategories.Category("de", "1");
        final var current = new LanguageCategories.Category("de", "2");
        before.remember(old);
        before.remember(current);
        before.remember(current);
        final var restarted = new LanguageCategories(Jdbis.over(database.dataSourceAs(DatabaseRole.DISCORD_BOT)));
        assertEquals(List.of(old, current), restarted.all());
        restarted.forget(old);
        assertEquals(List.of(current), restarted.all());
    }
}
