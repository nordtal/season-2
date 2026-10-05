package eu.nordtal.s2.discordbot.onboarding;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.discordbot.roles.GuildRoles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which language, region and lock role a member keeps, and what their record becomes, without a guild. */
class SettlementTest {

    private static final String EN = "10";
    private static final String DE = "20";
    private static final String BERLIN = "30";
    private static final String NEW_YORK = "31";
    private static final String LOCK = "40";

    private static final Choices CHOICES = new Choices(
            List.of(
                    new Choices.Choice(Choices.Kind.LANGUAGE, GuildRoles.language("en"), "English", "en"),
                    new Choices.Choice(Choices.Kind.LANGUAGE, GuildRoles.language("de"), "Deutsch", "de")),
            List.of(
                    new Choices.Choice(
                            Choices.Kind.REGION, GuildRoles.region("Europe/Berlin"), "Central Europe", "Europe/Berlin"),
                    new Choices.Choice(
                            Choices.Kind.REGION, GuildRoles.region("America/New_York"), "US East", "America/New_York")),
            "Onboarding");

    private final Map<String, String> guild = new HashMap<>(Map.of(
            GuildRoles.language("en"), EN,
            GuildRoles.language("de"), DE,
            GuildRoles.region("Europe/Berlin"), BERLIN,
            GuildRoles.region("America/New_York"), NEW_YORK));

    private Settlement settle(
            final Set<String> held, final Set<String> chosen, final Records.Recorded recorded, final boolean locking) {
        return Settlement.of(
                CHOICES, key -> Optional.ofNullable(guild.get(key)), held, chosen, recorded, LOCK, locking);
    }

    private static Records.Recorded recorded(final String language, final String zone) {
        return new Records.Recorded(language, zone);
    }

    @Test
    void aMemberWhoseRolesMatchTheirRecordChangesNothing() {
        assertTrue(settle(Set.of(EN, BERLIN), Set.of(), recorded("en", "Europe/Berlin"), true)
                .isEmpty());
    }

    @Test
    void theRoleJustAddedWinsAndTheOtherOfItsKindIsTaken() {
        final Settlement settlement = settle(Set.of(EN, DE, BERLIN), Set.of(DE), recorded("en", "Europe/Berlin"), true);

        assertAll(
                () -> assertEquals(Set.of(EN), settlement.remove()),
                () -> assertEquals(Set.of(), settlement.add()),
                () -> assertEquals(List.of(new Settlement.Change(Choices.Kind.LANGUAGE, "de")), settlement.record()));
    }

    @Test
    void withNothingJustAddedTheOneTheRecordHoldsIsKept() {
        final Settlement settlement = settle(Set.of(EN, DE, BERLIN), Set.of(), recorded("de", "Europe/Berlin"), true);

        assertAll(
                () -> assertEquals(Set.of(EN), settlement.remove()),
                () -> assertTrue(settlement.record().isEmpty()));
    }

    @Test
    void withNothingJustAddedAndNoRecordTheFirstOfferedIsKept() {
        final Settlement settlement = settle(Set.of(DE, EN, NEW_YORK, BERLIN), Set.of(), Records.Recorded.NONE, false);

        assertAll(
                () -> assertEquals(Set.of(DE, NEW_YORK), settlement.remove()),
                () -> assertEquals(
                        List.of(
                                new Settlement.Change(Choices.Kind.LANGUAGE, "en"),
                                new Settlement.Change(Choices.Kind.REGION, "Europe/Berlin")),
                        settlement.record()));
    }

    @Test
    void droppingBothRolesGivesTheRecordBackToTheNetwork() {
        final Settlement settlement = settle(Set.of(), Set.of(), recorded("de", "America/New_York"), false);

        assertEquals(
                List.of(
                        new Settlement.Change(Choices.Kind.LANGUAGE, null),
                        new Settlement.Change(Choices.Kind.REGION, null)),
                settlement.record());
    }

    @Test
    void aMemberWithoutARegionIsLockedWhileTheLockIsOn() {
        assertEquals(
                Set.of(LOCK),
                settle(Set.of(EN), Set.of(), recorded("en", null), true).add());
    }

    @Test
    void theLockRoleGoesTheMomentBothAreHeld() {
        final Settlement settlement = settle(Set.of(EN, LOCK, BERLIN), Set.of(BERLIN), recorded("en", null), true);

        assertAll(
                () -> assertEquals(Set.of(LOCK), settlement.remove()),
                () -> assertEquals(
                        List.of(new Settlement.Change(Choices.Kind.REGION, "Europe/Berlin")), settlement.record()));
    }

    @Test
    void withTheLockOffNobodyKeepsTheLockRoleChosenOrNot() {
        assertEquals(
                Set.of(LOCK),
                settle(Set.of(LOCK), Set.of(), Records.Recorded.NONE, false).remove());
    }

    @Test
    void aKindWithARoleTheBotCouldNotTakeIsLeftAloneAndLocksNobody() {
        // Two roles called "US East" in the guild: neither is taken, so a holder of one must not lose their zone.
        guild.remove(GuildRoles.region("America/New_York"));

        final Settlement settlement = settle(Set.of(EN), Set.of(), recorded("en", "America/New_York"), true);

        assertTrue(settlement.isEmpty(), settlement.toString());
    }

    @Test
    void aChoiceNotYetHeldIsGivenAndTheRestOfItsKindTaken() {
        final Settlement settlement = settle(Set.of(EN, BERLIN), Set.of(DE), recorded("en", "Europe/Berlin"), true);

        assertAll(
                () -> assertEquals(Set.of(DE), settlement.add()), () -> assertEquals(Set.of(EN), settlement.remove()));
    }

    @Test
    void aRoleThatIsNoChoiceIsNeverTouched() {
        final Settlement settlement =
                settle(Set.of(EN, BERLIN, "99"), Set.of("99"), recorded("en", "Europe/Berlin"), true);

        assertTrue(settlement.isEmpty(), settlement.toString());
    }

    @Test
    void theLockRoleNamesItselfLastAmongTheRolesTheChoicesNeed() {
        final List<GuildRoles.Wanted> wanted = CHOICES.wanted();

        assertAll(
                () -> assertEquals(5, wanted.size()),
                () -> assertEquals(new GuildRoles.Wanted(GuildRoles.LOCK, "Onboarding"), wanted.getLast()),
                () -> assertEquals(
                        new GuildRoles.Wanted(GuildRoles.region("Europe/Berlin"), "Central Europe"), wanted.get(2)));
    }
}
