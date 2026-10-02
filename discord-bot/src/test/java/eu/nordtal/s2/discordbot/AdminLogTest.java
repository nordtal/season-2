package eu.nordtal.s2.discordbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.alert.Alert;
import java.util.List;
import org.junit.jupiter.api.Test;

/** An alert pings exactly the admins steward named, and an all-clear names nobody. */
class AdminLogTest {

    @Test
    void everyNamedAdminIsMentionedOutsideTheEmbed() {
        assertEquals(
                "<@400000000000000001> <@400000000000000002>",
                AdminLog.mentions(List.of(DiscordId.of("400000000000000001"), DiscordId.of("400000000000000002"))));
    }

    @Test
    void nobodyNamedMeansNoMention() {
        assertNull(AdminLog.mentions(List.of()));
    }

    @Test
    void theLevelIsTheEmojiTheRestOfTheChannelUses() {
        assertEquals("🛑", AdminLog.emoji(Alert.Level.DOWN));
        assertEquals("⚠️", AdminLog.emoji(Alert.Level.WARN));
        assertEquals("✅", AdminLog.emoji(Alert.Level.OK));
    }
}
