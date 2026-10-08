package eu.nordtal.season.discordbot.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.discordbot.onboarding.LockedChannels.Holder;
import eu.nordtal.season.discordbot.onboarding.LockedChannels.Overwrite;
import eu.nordtal.season.discordbot.onboarding.LockedChannels.View;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which overwrites the bot keeps on a channel, so the lock role and nobody else sees the onboarding channel. */
class LockedChannelsTest {

    private static final List<Overwrite> ONBOARDING = List.of(
            new Overwrite(Holder.BOT, View.OPEN),
            new Overwrite(Holder.LOCK, View.OPEN),
            new Overwrite(Holder.EVERYONE, View.CLOSED));

    @Test
    void theOnboardingChannelIsTheLockRolesAloneSoAReleasedMemberNoLongerSeesIt() {
        assertEquals(ONBOARDING, LockedChannels.wanted(true, true));
    }

    @Test
    void theOnboardingChannelStaysHiddenWhileTheLockIsOffSinceNobodyNeedsItThen() {
        assertEquals(ONBOARDING, LockedChannels.wanted(true, false));
    }

    @Test
    void theBotOpensTheOnboardingChannelToItselfBeforeClosingItToEveryone() {
        final List<Holder> order = LockedChannels.wanted(true, true).stream()
                .map(Overwrite::holder)
                .toList();

        assertEquals(0, order.indexOf(Holder.BOT));
        assertEquals(order.size() - 1, order.indexOf(Holder.EVERYONE));
    }

    @Test
    void everyOtherChannelIsClosedToTheLockRoleWhileTheLockIsOn() {
        assertEquals(List.of(new Overwrite(Holder.LOCK, View.CLOSED)), LockedChannels.wanted(false, true));
    }

    @Test
    void withTheLockOffEveryOtherChannelIsLeftAsItIs() {
        assertEquals(List.of(), LockedChannels.wanted(false, false));
    }
}
