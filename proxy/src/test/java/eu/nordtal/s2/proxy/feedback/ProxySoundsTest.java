package eu.nordtal.s2.proxy.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.Player;
import eu.nordtal.s2.common.feedback.Feedback;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.sound.Sound;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mapping table {@link ProxySounds} adds: {@link Feedback#REFUSED} plays a real
 * Adventure {@link Sound} and every other category stays silent, the same shape
 * {@code FeedbackSoundsTest} already holds {@code smp} and {@code hunger-games} to.
 *
 * <h2>Why a dynamic proxy can observe this without a real client</h2>
 * {@code Player#playSound(Sound)} is a default method inherited from Adventure's
 * {@code Audience}, but a {@link java.lang.reflect.Proxy}'s {@link java.lang.reflect.InvocationHandler}
 * is consulted for every interface method Velocity's {@code Player} declares or inherits, default or
 * not - it does not run the real default body unless the handler chooses to. That is exactly what
 * this test needs: it is not asserting that Adventure's own {@code playSound} does anything, only
 * that {@code ProxySounds} calls it with the sound this table says {@code REFUSED} means.
 */
class ProxySoundsTest {

    private static final UUID SOMEBODY = UUID.fromString("00000000-0000-4000-8000-000000000005");

    @Test
    @DisplayName("REFUSED plays the shared refusal sound smp and hunger-games also use")
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
    @DisplayName("every category this table has not declared plays nothing")
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
