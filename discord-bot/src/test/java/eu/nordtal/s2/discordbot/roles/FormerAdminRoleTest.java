package eu.nordtal.s2.discordbot.roles;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** When the bot takes the admin role the environment named before roles were found by name, and when it does not. */
class FormerAdminRoleTest {

    private static final Predicate<String> GUILD = Set.of("14")::contains;

    @Test
    void theConfiguredAdminRoleIsTakenWhenNoneIsStored() {
        assertEquals(Optional.of("14"), FormerAdminRole.toAdopt(false, " 14 ", GUILD));
    }

    @Test
    void aStoredAdminRoleIsNeverReplacedByTheVariable() {
        // The update takes it once; after that the stored id is the role, whatever the variable still says.
        assertTrue(FormerAdminRole.toAdopt(true, "14", GUILD).isEmpty());
    }

    @Test
    void anythingButARoleTheGuildHasLeavesTheAdminRoleToBeFoundByName() {
        assertAll(
                () -> assertTrue(FormerAdminRole.toAdopt(false, null, GUILD).isEmpty()),
                () -> assertTrue(FormerAdminRole.toAdopt(false, "", GUILD).isEmpty()),
                () -> assertTrue(FormerAdminRole.toAdopt(false, "<@&14>", GUILD).isEmpty()),
                () -> assertTrue(FormerAdminRole.toAdopt(false, "15", GUILD).isEmpty()));
    }
}
