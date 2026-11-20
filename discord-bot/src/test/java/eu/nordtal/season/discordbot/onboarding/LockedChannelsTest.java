package eu.nordtal.season.discordbot.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.discordbot.onboarding.LockedChannels.Holder;
import eu.nordtal.season.discordbot.onboarding.LockedChannels.Overwrite;
import eu.nordtal.season.discordbot.onboarding.LockedChannels.View;
import java.util.List;
import org.junit.jupiter.api.Test;

class LockedChannelsTest {

    @Test
    void theChooseChannelIsOpenToTheBotAndTheLockRoleAndClosedToEveryoneElse() {
        assertEquals(
                List.of(
                        new Overwrite(Holder.BOT, View.OPEN),
                        new Overwrite(Holder.LOCK, View.OPEN),
                        new Overwrite(Holder.EVERYONE, View.CLOSED)),
                LockedChannels.wanted(true));
    }

    @Test
    void everyOtherChannelIsClosedToTheLockRoleTheLockOnOrOffSoSwitchingItOnRewritesNoChannel() {
        assertEquals(List.of(new Overwrite(Holder.LOCK, View.CLOSED)), LockedChannels.wanted(false));
    }
}
