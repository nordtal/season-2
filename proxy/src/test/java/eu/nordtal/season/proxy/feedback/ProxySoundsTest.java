package eu.nordtal.season.proxy.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.Player;
import eu.nordtal.season.messages.feedback.Feedback;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.sound.Sound;
import org.junit.jupiter.api.Test;

/**
 * {@link Feedback#REFUSED} plays a real {@link Sound} and every other category stays silent.
 *
 * A dynamic proxy intercepts {@code Player#playSound} even though it is a default method.
 */
class ProxySoundsTest {

    private static final UUID SOMEBODY = UUID.fromString("00000000-0000-4000-8000-000000000005");

    @Test
    void refusedPlaysTheSharedSound() {
        final List<Sound> played = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        final ProxySounds sounds = ProxySounds.defaults(problems::add);

        sounds.play(player(played), Feedback.REFUSED);

        assertEquals(1, played.size(), played.toString());
        assertEquals("minecraft:block.note_block.bass", played.getFirst().name().asString());
        assertEquals(1.0f, played.getFirst().volume());
        assertEquals(0.7f, played.getFirst().pitch());
        assertEquals(List.of(), problems, problems.toString());
    }

    @Test
    void everythingElseIsSilent() {
        final List<Sound> played = new ArrayList<>();
        final ProxySounds sounds = ProxySounds.defaults(problem -> {
            throw new AssertionError("nothing to complain about: " + problem);
        });

        for (final Feedback category : Feedback.values()) {
            if (category == Feedback.REFUSED) {
                continue;
            }
            sounds.play(player(played), category);
        }

        assertTrue(played.isEmpty(), "only REFUSED is declared today: " + played);
    }

    private static Player player(final List<Sound> played) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) {
                        return SOMEBODY;
                    }
                    if ("playSound".equals(method.getName()) && args.length == 1) {
                        played.add((Sound) args[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
