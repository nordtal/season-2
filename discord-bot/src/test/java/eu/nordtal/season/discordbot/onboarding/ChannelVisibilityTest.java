package eu.nordtal.season.discordbot.onboarding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ChannelVisibilityTest {
    @Test
    void aRoleOrMemberAllowIsReportedOnlyWhenItExposesAProtectedMember() {
        assertTrue(ChannelVisibility.leaks(Set.of("locked", "other-language"), Set.of("locked"), false));
        assertTrue(ChannelVisibility.leaks(Set.of("locked", "other-language"), Set.of("other-language"), false));
        assertFalse(ChannelVisibility.leaks(Set.of("locked"), Set.of("released"), false));
        assertFalse(ChannelVisibility.leaks(Set.of(), Set.of("released"), false));
    }

    @Test
    void everyoneDoesNotOverrideTheBotRolesDenials() {
        assertFalse(ChannelVisibility.leaks(Set.of("locked"), Set.of("locked"), true));
    }

    @Test
    void categoryDenialsReachTheCategoryAndItsChildrenOnly() {
        assertTrue(LanguageChannels.belongs("category", null, "category"));
        assertTrue(LanguageChannels.belongs("rules", "category", "category"));
        assertFalse(LanguageChannels.belongs("chat", "shared", "category"));
        assertFalse(LanguageChannels.belongs("voice", null, "category"));
    }
}
