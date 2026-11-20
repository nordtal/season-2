package eu.nordtal.season.papercommon.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Every cue has a sound with a valid key, the switch sounds higher on than off, and a tick keeps to its voice. */
class DialogueSoundsTest {

    @Test
    void everyCueHasAMinecraftSound() {
        for (final Effect.Cue cue : Effect.Cue.values()) {
            final DialogueSounds.Tone tone = DialogueSounds.tone(cue);
            assertTrue(tone.key().startsWith("minecraft:"), cue.name());
            assertTrue(tone.volume() > 0 && tone.volume() <= 1, cue.name());
        }
    }

    @Test
    void theSwitchSoundsHigherWhenItTicks() {
        assertTrue(DialogueSounds.tone(Effect.Cue.SWITCH_ON).pitch()
                > DialogueSounds.tone(Effect.Cue.SWITCH_OFF).pitch());
        assertEquals(
                "minecraft:block.note_block.hat",
                DialogueSounds.tone(Effect.Cue.TYPE).key());
    }

    @Test
    void aTypingTickStraysAtMostThreePercentFromTheVoice() {
        assertEquals(1.5f, DialogueSounds.typing(1.5f, 0), 1e-6);
        assertEquals(1.5f * 1.03f, DialogueSounds.typing(1.5f, 1), 1e-6);
        assertEquals(1.5f * 0.97f, DialogueSounds.typing(1.5f, -1), 1e-6);
    }
}
