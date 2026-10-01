package eu.nordtal.s2.smp.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.spec.Specs;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.papercommon.sound.SoundsSpec;
import eu.nordtal.s2.settings.Group;
import eu.nordtal.s2.settings.MemorySettingStore;
import eu.nordtal.s2.settings.Setting;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** That the smp's sounds answer every category out of the box, and that an admin's blank key silences one. */
class SmpSoundsTest {

    private static final Group<SoundsSpec> SOUNDS =
            Group.of("sounds", SoundsSpec.class).whileRunning();

    private final MemorySettingStore store = new MemorySettingStore();

    @Test
    void nothingIsSilentByDefault() {
        final List<String> problems = new ArrayList<>();
        final SmpSounds sounds = SmpSounds.of(Specs.createDefault(SoundsSpec.class), problems::add);

        assertEquals(
                List.of(),
                problems,
                "a shipped default that the parser has to correct is a default that was never checked");
        for (final Feedback category : Feedback.values()) {
            if (category == Feedback.STAGING) {
                // Silent on purpose: a staged moment's sound has no chime to borrow yet.
                assertTrue(
                        sounds.isSilent(category),
                        "STAGING is no longer silent out of the box, which is a decision and not a tidy-up");
                continue;
            }
            assertFalse(sounds.isSilent(category), category + " is silent out of the box");
        }
    }

    /** A blank key an admin stores is picked up on the one {@code SmpSounds} every listener already holds. */
    @Test
    void aBlankedKeySilencesItsCategoryOnTheRunningInstance() throws Exception {
        final Setting<SoundsSpec> handle = store.settings("smp").load(SOUNDS);
        final List<String> problems = new ArrayList<>();
        final SmpSounds running = SmpSounds.of(handle.get(), problems::add);
        assertFalse(running.isSilent(Feedback.SELECT), "it has to start audible for this to prove anything");

        store.set("smp", "sounds", "select.key", "");
        handle.reload();
        running.reload(handle.get());

        assertEquals("", handle.get().select().key());
        assertTrue(running.isSilent(Feedback.SELECT));
        assertFalse(running.isSilent(Feedback.TRAVEL), "only the blanked category goes quiet");
        assertEquals(List.of(), problems, "silencing a category on purpose must not read as a misconfiguration");
    }
}
