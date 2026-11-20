package eu.nordtal.season.papercommon.npc;

import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;

/**
 * The only class in the dialogue that names a sound. The typing sound takes the NPC's voice, within 3 % either way.
 * They are the reviewed design's own interface sounds, not feedback, so no category of the server's sounds holds them.
 */
public final class DialogueSounds {

    /** How far a typing tick strays from the voice, as a share of it, so the ticks do not sound mechanical. */
    static final double TYPING_SPREAD = 0.03;

    /** One sound: its key, its volume and its pitch. */
    record Tone(String key, float volume, float pitch) {}

    /** Returns the sound of {@code cue}; the typing sound before the voice and its spread. */
    static Tone tone(final Effect.Cue cue) {
        return switch (cue) {
            case TYPE -> new Tone("minecraft:block.note_block.hat", 0.18f, 1.0f);
            case OPEN -> new Tone("minecraft:item.book.page_turn", 0.9f, 0.85f);
            case NEXT_TEXT -> new Tone("minecraft:item.book.page_turn", 0.45f, 1.25f);
            case SELECTION -> new Tone("minecraft:block.wooden_button.click_on", 0.35f, 1.7f);
            case ANSWER -> new Tone("minecraft:ui.button.click", 0.4f, 1.2f);
            case SWITCH_ON -> new Tone("minecraft:ui.button.click", 0.4f, 1.4f);
            case SWITCH_OFF -> new Tone("minecraft:ui.button.click", 0.4f, 1.0f);
            case CLOSE -> new Tone("minecraft:item.book.put", 0.7f, 1.0f);
        };
    }

    /** Plays {@code cue} to {@code player} alone; {@code voice} is the pitch of the NPC that types. */
    public void play(final Player player, final Effect.Cue cue, final float voice) {
        final Tone tone = tone(cue);
        final float pitch = cue == Effect.Cue.TYPE
                ? typing(voice, ThreadLocalRandom.current().nextDouble(-1, 1))
                : tone.pitch();
        player.playSound(
                Sound.sound(Key.key(tone.key()), Sound.Source.MASTER, tone.volume(), pitch), Sound.Emitter.self());
    }

    /** Returns a typing tick's pitch: {@code voice}, strayed by {@code stray} from -1 to 1 times the spread. */
    static float typing(final float voice, final double stray) {
        return (float) (voice * (1 + TYPING_SPREAD * stray));
    }
}
