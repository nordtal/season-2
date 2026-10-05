package eu.nordtal.season.discordbot.roles;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.alert.Alert;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** How the bot finds, adopts, creates and then follows the roles it uses, with a guild that is a map. */
class GuildRolesTest {

    /** The guild's roles by id, each with its name; created roles get the next free id. */
    private static final class Guild implements GuildRoles.RoleList {

        private final Map<String, String> names = new LinkedHashMap<>();
        private final List<String> created = new ArrayList<>();
        private boolean refusing;

        Guild with(final String roleId, final String name) {
            names.put(roleId, name);
            return this;
        }

        @Override
        public boolean has(final String roleId) {
            return names.containsKey(roleId);
        }

        @Override
        public List<String> named(final String name) {
            return names.entrySet().stream()
                    .filter(role -> role.getValue().equals(name))
                    .map(Map.Entry::getKey)
                    .toList();
        }

        @Override
        public String create(final String name) {
            if (refusing) {
                throw new IllegalStateException("Missing permission MANAGE_ROLES");
            }
            final String roleId = String.valueOf(900 + created.size());
            names.put(roleId, name);
            created.add(name);
            return roleId;
        }
    }

    /** The table, as a map. */
    private static final class Table implements DiscordRoleDao {

        private final Map<String, String> rows = new LinkedHashMap<>();

        @Override
        public Map<String, String> all() {
            return Map.copyOf(rows);
        }

        @Override
        public void store(final String key, final String roleId) {
            rows.put(key, roleId);
        }
    }

    private final Table table = new Table();
    private final List<Alert> alerts = new ArrayList<>();

    private GuildRoles roles() {
        return new GuildRoles(table, alerts::add);
    }

    private static List<GuildRoles.Wanted> access() {
        return List.of(new GuildRoles.Wanted(GuildRoles.ACCESS, "Access"));
    }

    @Test
    void theOneRoleOfExactlyTheNameIsTakenAndStored() {
        final Guild guild = new Guild().with("10", "Access").with("11", "access");

        roles().resolve(guild, access());

        assertAll(
                () -> assertEquals(Map.of(GuildRoles.ACCESS, "10"), table.all()),
                () -> assertTrue(guild.created.isEmpty()),
                () -> assertTrue(alerts.isEmpty()));
    }

    @Test
    void aRoleNobodyMadeYetIsCreatedOnceAndThenFollowed() {
        final Guild guild = new Guild();
        final GuildRoles roles = roles();

        roles.resolve(guild, access());
        roles.resolve(guild, access());

        assertAll(
                () -> assertEquals(List.of("Access"), guild.created),
                () -> assertEquals(Map.of(GuildRoles.ACCESS, "900"), table.all()));
    }

    @Test
    void aStoredRoleIsFollowedByItsIdHoweverItIsCalledNow() {
        // An admin renamed it, and somebody made a new role of the old name: the stored one stays the role.
        table.store(GuildRoles.ACCESS, "10");
        final Guild guild = new Guild().with("10", "Members").with("12", "Access");

        roles().resolve(guild, access());

        assertAll(
                () -> assertEquals("10", table.all().get(GuildRoles.ACCESS)),
                () -> assertTrue(guild.created.isEmpty()));
    }

    @Test
    void aStoredRoleThatIsGoneIsLookedUpByItsNameAgain() {
        table.store(GuildRoles.ACCESS, "10");
        final Guild guild = new Guild().with("12", "Access");

        roles().resolve(guild, access());

        assertEquals("12", table.all().get(GuildRoles.ACCESS));
    }

    @Test
    void twoRolesOfTheNameAreNeitherTakenAndTheAdminsAreToldOnce() {
        final Guild guild = new Guild().with("10", "Access").with("11", "Access");
        final GuildRoles roles = roles();

        roles.resolve(guild, access());
        roles.resolve(guild, access());

        assertAll(
                () -> assertTrue(table.all().isEmpty()),
                () -> assertTrue(guild.created.isEmpty(), "creating a third would only make it worse"),
                () -> assertEquals(1, alerts.size()),
                () -> assertTrue(roles.id(GuildRoles.ACCESS).isEmpty()));
    }

    @Test
    void oneOfTwoRolesOfTheNameIsTakenOnceTheOtherIsGone() {
        final Guild guild = new Guild().with("10", "Access").with("11", "Access");
        final GuildRoles roles = roles();
        roles.resolve(guild, access());

        guild.names.remove("11");
        roles.resolve(guild, access());

        assertEquals("10", table.all().get(GuildRoles.ACCESS));
    }

    @Test
    void aRoleAnotherKeyAlreadyStandsForIsNeverTakenForASecond() {
        // A region renamed in Steward to an existing language's name gets a role of its own.
        table.store(GuildRoles.language("de"), "20");
        final Guild guild = new Guild().with("20", "Deutsch");

        roles().resolve(guild, List.of(new GuildRoles.Wanted(GuildRoles.region("Europe/Berlin"), "Deutsch")));

        assertAll(
                () -> assertEquals("20", table.all().get(GuildRoles.language("de"))),
                () -> assertEquals("900", table.all().get(GuildRoles.region("Europe/Berlin"))));
    }

    @Test
    void aRoleThatCannotBeCreatedIsToldOnceAndTriedAgainOnTheNextSweep() {
        final Guild guild = new Guild();
        guild.refusing = true;
        final GuildRoles roles = roles();

        roles.resolve(guild, access());
        roles.resolve(guild, access());
        guild.refusing = false;
        roles.resolve(guild, access());

        assertAll(
                () -> assertEquals(1, alerts.size()),
                () -> assertEquals(Optional.of("900"), roles.id(GuildRoles.ACCESS)));
    }

    @Test
    void theStoredRolesSurviveARestart() {
        roles().resolve(new Guild().with("10", "Access"), access());

        final GuildRoles restarted = roles();

        assertAll(
                () -> assertEquals(Optional.of("10"), restarted.id(GuildRoles.ACCESS)),
                () -> assertTrue(restarted.isStored("10")),
                () -> assertFalse(restarted.isStored("11")));
    }
}
