package eu.nordtal.season.papercommon.npc;

import java.util.ArrayList;
import java.util.List;

/**
 * When each character of a text appears as an NPC types it, and how long the text holds once typed.
 * A run of marks pauses once, by its longest pause, and nothing pauses after the last character.
 */
public final class Typing {

    static final long LONG_PAUSE = 280;
    static final long MIDDLE_PAUSE = 200;
    static final long SHORT_PAUSE = 120;
    static final long HOLD = 1200;
    static final long HOLD_PER_CHARACTER = 40;
    static final long LONGEST_HOLD = 6000;
    static final long SHORTEST_HAND_OFF = 3000;

    private final String text;
    private final long[] shownAt;
    private final boolean[] sounds;

    private Typing(final String text, final long[] shownAt, final boolean[] sounds) {
        this.text = text;
        this.shownAt = shownAt;
        this.sounds = sounds;
    }

    /** Types {@code lines} one after the other at {@code perSecond} characters a second. */
    public static Typing of(final List<String> lines, final int perSecond) {
        if (perSecond <= 0) {
            throw new IllegalArgumentException("a typing speed is above 0, not " + perSecond);
        }
        final String text = String.join("", lines);
        final long[] shownAt = new long[text.length()];
        final boolean[] sounds = new boolean[text.length()];
        long pauses = 0;
        long runPause = 0;
        int letters = 0;
        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            final long pause = pause(character);
            if (pause == 0) {
                pauses += runPause;
                runPause = 0;
            } else {
                runPause = Math.max(runPause, pause);
            }
            shownAt[index] = Math.round((index + 1) * 1000.0 / perSecond) + pauses;
            if (Character.isLetterOrDigit(character)) {
                letters++;
                sounds[index] = letters % 2 == 0;
            }
        }
        return new Typing(text, shownAt, sounds);
    }

    /** Returns the pause after {@code character} in milliseconds, 0 for a character that is no mark. */
    static long pause(final char character) {
        return switch (character) {
            case '.', '!', '?', '…' -> LONG_PAUSE;
            case ':', ';' -> MIDDLE_PAUSE;
            case ',' -> SHORT_PAUSE;
            default -> 0;
        };
    }

    public int length() {
        return text.length();
    }

    /** Returns how many characters show {@code elapsed} milliseconds after typing began. */
    public int shown(final long elapsed) {
        int count = 0;
        while (count < shownAt.length && shownAt[count] <= elapsed) {
            count++;
        }
        return count;
    }

    /** Returns the millisecond the last character appears, 0 for an empty text. */
    public long typedAt() {
        return shownAt.length == 0 ? 0 : shownAt[shownAt.length - 1];
    }

    /** Returns how long the typed text waits before the next one. */
    public long hold() {
        return Math.min(LONGEST_HOLD, HOLD + HOLD_PER_CHARACTER * text.length());
    }

    /** Returns how long the last text waits before its menu opens by itself. */
    public long handOffHold() {
        return Math.max(SHORTEST_HAND_OFF, hold());
    }

    /** Returns whether a character after the first {@code before} up to {@code after} plays the typing sound. */
    public boolean soundsBetween(final int before, final int after) {
        for (int index = Math.max(0, before); index < Math.min(after, sounds.length); index++) {
            if (sounds[index]) {
                return true;
            }
        }
        return false;
    }

    /** Returns the first {@code count} characters of {@code lines}, line by line. */
    public static List<String> shownLines(final List<String> lines, final int count) {
        final List<String> shown = new ArrayList<>();
        int left = count;
        for (final String line : lines) {
            shown.add(line.substring(0, Math.max(0, Math.min(line.length(), left))));
            left -= line.length();
        }
        return List.copyOf(shown);
    }
}
