package eu.nordtal.s2.commands.announce;

import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Values;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnnounceCommandTest {

    private static final class Posts implements AnnounceEffects {
        final List<String> posted = new ArrayList<>();
        final Set<String> channels;

        Posts(final Set<String> channels) {
            this.channels = channels;
        }

        @Override
        public void async(final Runnable work) {
            work.run();
        }

        @Override
        public void warn(final String what, final Throwable cause) {
        }

        @Override
        public boolean post(final String languageTag, final String text) {
            if (!channels.contains(languageTag)) {
                return false;
            }
            posted.add(languageTag + ": " + text);
            return true;
        }
    }

    private static Values values(final String language, final String text) {
        return new Values(AnnounceCommands.ANNOUNCE, Map.of("language", language, "text", text));
    }

    @Test
    @DisplayName("a line goes into the channel of the language it was rendered in")
    void postsIntoTheLanguagesChannel() {
        final Posts posts = new Posts(Set.of("de", "en"));
        final FakeUser user = FakeUser.console();
        new Announce().run(user, values("de", "Aufbruch ist geschafft."), posts);
        assertEquals(List.of("de: Aufbruch ist geschafft."), posts.posted);
        assertEquals("announce.posted", user.only().key());
    }

    @Test
    @DisplayName("a language without a channel is said so in the row, not silently dropped")
    void noChannelIsAnAnswer() {
        final Posts posts = new Posts(Set.of("en"));
        final FakeUser user = FakeUser.console();
        new Announce().run(user, values("de", "Aufbruch ist geschafft."), posts);
        assertEquals(List.of(), posts.posted);
        assertEquals("announce.no-channel", user.only().key());
        assertEquals("de", user.only().placeholders().get("language"));
    }

    @Test
    @DisplayName("announce is typed nowhere: SYSTEM only, so no adapter registers it")
    void isSystemOnly() {
        assertEquals(Set.of(Surface.SYSTEM), AnnounceCommands.ANNOUNCE.surfaces());
    }
}
