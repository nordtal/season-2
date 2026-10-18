package eu.nordtal.season.discordbot.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.discordbot.onboarding.LockedChannels.Holder;
import eu.nordtal.season.discordbot.onboarding.LockedChannels.Overwrite;
import eu.nordtal.season.discordbot.onboarding.LockedChannels.View;
import java.util.List;
import org.junit.jupiter.api.Test;

class LockedChannelsTest {

    private static final List<Overwrite> ONBOARDING = List.of(
            new Overwrite(Holder.BOT, View.OPEN),
            new Overwrite(Holder.LOCK, View.OPEN),
            new Overwrite(Holder.EVERYONE, View.CLOSED));

    @Test
    void theOnboardingChannelIsTheLockRolesAloneSoAReleasedMemberNoLongerSeesIt() {
        assertEquals(ONBOARDING, LockedChannels.wanted(true));
    }

    @Test
    void theBotOpensTheOnboardingChannelToItselfBeforeClosingItToEveryone() {
        final List<Holder> order =
                LockedChannels.wanted(true).stream().map(Overwrite::holder).toList();

        assertEquals(0, order.indexOf(Holder.BOT));
        assertEquals(order.size() - 1, order.indexOf(Holder.EVERYONE));
    }

    @Test
    void everyOtherChannelIsClosedToTheLockRoleTheLockOnOrOffSoSwitchingItOnRewritesNoChannel() {
        assertEquals(List.of(new Overwrite(Holder.LOCK, View.CLOSED)), LockedChannels.wanted(false));
    }
}
