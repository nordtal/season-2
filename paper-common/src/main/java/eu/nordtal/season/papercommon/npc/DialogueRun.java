package eu.nordtal.season.papercommon.npc;

import eu.nordtal.season.database.npc.Dialogue;
import eu.nordtal.season.database.npc.Dialogue.Step;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.npc.DialogueScreen;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Hint;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Mark;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One player's dialogue with one NPC, tick by tick: what it shows, and what each input does.
 * It knows no server: the game feeds it ticks and inputs and carries out the {@link Effect}s of each {@link Frame}.
 */
public final class DialogueRun {

    static final long TICK = 50;
    static final int STAGE_TICKS = 2;
    static final int ZOOM_IN_TICKS = 8;
    static final int ZOOM_OUT_TICKS = 6;
    static final int ANSWERS_AFTER = 5;
    static final int CLICK_AFTER = 5;
    static final int DEBOUNCE = 4;
    static final int BLINK = 10;

    private enum Phase {
        RUNNING,
        CLOSING,
        HANDED_OFF,
        ENDED
    }

    private final Dialogue dialogue;
    private final String name;
    private final String portraitFont;
    private final Locale locale;
    private final Feel feel;
    private final Labels labels;
    private final @Nullable String skipped;
    private final List<Effect> pending = new ArrayList<>();

    private Phase phase = Phase.RUNNING;
    private boolean ticked;
    private boolean shiftDown;
    private long age;
    private int bars;
    private int vignette;
    private double zoom;
    private long lastClick = -DEBOUNCE;

    private Step step;
    private List<String> lines;
    private Typing typing;
    private long stepStart = -1;
    private int shown;
    private long typedAt = -1;
    private long holdFrom;
    private int selected;
    private boolean answering;

    /**
     * Starts the dialogue of an NPC called {@code name} for a reader of {@code locale}.
     * {@code skipped} is the menu the player skipped to before, {@code ticked} how the box starts, and
     * {@code shiftDown} whether Shift is held already, which then counts only once let go and pressed again.
     */
    public DialogueRun(
            final Dialogue dialogue,
            final String name,
            final Locale locale,
            final Feel feel,
            final Labels labels,
            final @Nullable String skipped,
            final boolean ticked,
            final boolean shiftDown) {
        this(dialogue, name, Glyphs.FONT_NPC, locale, feel, labels, skipped, ticked, shiftDown);
    }

    /** Starts the dialogue with {@code portraitFont}, the default font or the one of the NPC's uploaded skin. */
    public DialogueRun(
            final Dialogue dialogue,
            final String name,
            final String portraitFont,
            final Locale locale,
            final Feel feel,
            final Labels labels,
            final @Nullable String skipped,
            final boolean ticked,
            final boolean shiftDown) {
        this.dialogue = Objects.requireNonNull(dialogue, "dialogue");
        this.name = Objects.requireNonNull(name, "name");
        this.portraitFont = Objects.requireNonNull(portraitFont, "portraitFont");
        this.locale = Objects.requireNonNull(locale, "locale");
        this.feel = Objects.requireNonNull(feel, "feel");
        this.labels = Objects.requireNonNull(labels, "labels");
        this.skipped = skipped;
        this.ticked = ticked;
        this.shiftDown = shiftDown;
        this.step = dialogue.step(dialogue.start())
                .orElseThrow(() -> new IllegalArgumentException("the dialogue starts at a step it does not have"));
        this.lines = page(step);
        this.typing = Typing.of(lines, feel.typingSpeed());
        pending.add(new Effect.Sound(Effect.Cue.OPEN));
    }

    /** Returns whether {@code dialogue} has the step it starts at. */
    public static boolean playable(final Dialogue dialogue) {
        return dialogue.step(dialogue.start()).isPresent();
    }

    /** Runs one tick with the player standing {@code where}, and returns what shows on it. */
    public Frame tick(final Where where) {
        age++;
        switch (phase) {
            case RUNNING -> run(where);
            case CLOSING -> fade(true);
            case HANDED_OFF -> fade(false);
            case ENDED -> {}
        }
        final List<Effect> effects = List.copyOf(pending);
        pending.clear();
        return new Frame(bars, vignette, zoom, panel(), hints(), toggle(), effects);
    }

    /** A right-click: finishes the text, goes on, or at a hand-off opens the menu at once. */
    public void rightClick() {
        if (!listening() || !debounced()) {
            return;
        }
        if (typedAt < 0) {
            shown = typing.length();
            typed();
        } else if (step.handsOff()) {
            handOff();
        } else if (step.answers().isEmpty()) {
            goOn(step.next());
        }
    }

    /** A left-click: takes the selected answer once the answers have shown for a moment. */
    public void leftClick() {
        if (!listening() || !answering || age - typedAt < ANSWERS_AFTER + CLICK_AFTER || !debounced()) {
            return;
        }
        pending.add(new Effect.Sound(Effect.Cue.ANSWER));
        release();
        goOn(step.answers().get(selected).next());
    }

    /** The hotbar went to {@code slot}; returns whether the dialogue took it, so the slot stays held. */
    public boolean select(final int slot) {
        if (!answering || phase != Phase.RUNNING) {
            return false;
        }
        final int before = selected;
        selected = Hotbar.select(selected, answers().size(), slot);
        if (selected != before) {
            pending.add(new Effect.Sound(Effect.Cue.SELECTION));
        }
        return true;
    }

    /** Shift went down or up. A press that begins on a hand-off ticks or unticks the box and holds it anew. */
    public void sneak(final boolean down) {
        final boolean pressed = down && !shiftDown;
        shiftDown = down;
        if (!pressed || !listening() || !step.handsOff()) {
            return;
        }
        ticked = !ticked;
        pending.add(new Effect.Sound(ticked ? Effect.Cue.SWITCH_ON : Effect.Cue.SWITCH_OFF));
        if (typedAt >= 0) {
            holdFrom = age;
        }
    }

    /** Ends the dialogue softly: the panel goes and the bars slide out. */
    public void close() {
        if (phase != Phase.RUNNING) {
            return;
        }
        phase = Phase.CLOSING;
        release();
        pending.add(new Effect.Sound(Effect.Cue.CLOSE));
    }

    /** Returns whether answers show, so the hotbar is held. */
    public boolean answering() {
        return answering;
    }

    /** Returns whether the dialogue still takes input: it runs and its panel shows. */
    public boolean listening() {
        return phase == Phase.RUNNING && stepStart >= 0;
    }

    /** Returns whether the dialogue ended softly or handed off, and nothing of it shows any more. */
    public boolean over() {
        return phase == Phase.ENDED;
    }

    private void run(final Where where) {
        if (where.beyond() > feel.endDistance() || where.angle() > feel.endAngle()) {
            close();
            fade(true);
            return;
        }
        bars = Math.min(DialogueScreen.STAGES, 1 + (int) ((age - 1) / STAGE_TICKS));
        vignette = bars;
        zoom = Math.min(1.0, (double) age / ZOOM_IN_TICKS);
        if (bars < DialogueScreen.STAGES) {
            return;
        }
        if (stepStart < 0) {
            stepStart = age;
        }
        if (typedAt < 0) {
            final int before = shown;
            shown = typing.shown((age - stepStart) * TICK);
            if (typing.soundsBetween(before, shown)) {
                pending.add(new Effect.Sound(Effect.Cue.TYPE));
            }
            if (shown >= typing.length()) {
                typed();
            }
        } else if (!step.answers().isEmpty()) {
            if (!answering && age - typedAt >= ANSWERS_AFTER) {
                answering = true;
                selected = 0;
                pending.add(new Effect.HoldSlot());
            }
        } else if (step.handsOff()) {
            if (age - holdFrom >= ticks(typing.handOffHold())) {
                handOff();
            }
        } else if (age - holdFrom >= ticks(typing.hold())) {
            goOn(step.next());
        }
    }

    private void fade(final boolean closing) {
        if (closing) {
            bars = Math.max(0, bars - 1);
        } else {
            bars = 0;
        }
        vignette = Math.max(0, vignette - 1);
        zoom = Math.max(0.0, zoom - 1.0 / ZOOM_OUT_TICKS);
        if (bars == 0 && vignette == 0 && zoom <= 1e-9) {
            zoom = 0.0;
            phase = Phase.ENDED;
            pending.add(new Effect.End());
        }
    }

    private void typed() {
        typedAt = age;
        holdFrom = age;
    }

    private void goOn(final @Nullable String next) {
        final Step following = next == null ? null : dialogue.step(next).orElse(null);
        if (following == null) {
            close();
            return;
        }
        step = following;
        lines = page(following);
        typing = Typing.of(lines, feel.typingSpeed());
        stepStart = age;
        shown = 0;
        typedAt = -1;
        selected = 0;
        pending.add(new Effect.Sound(Effect.Cue.NEXT_TEXT));
    }

    private void handOff() {
        final String menu = Objects.requireNonNull(step.menu(), "menu");
        release();
        phase = Phase.HANDED_OFF;
        bars = 0;
        pending.add(new Effect.HandOff(menu, SkipChoice.handOff(menu, ticked, skipped)));
    }

    private void release() {
        if (answering) {
            answering = false;
            pending.add(new Effect.ReleaseSlot());
        }
    }

    private boolean debounced() {
        if (age - lastClick < DEBOUNCE) {
            return false;
        }
        lastClick = age;
        return true;
    }

    private DialogueScreen.@Nullable Panel panel() {
        if (!listening() || bars < DialogueScreen.STAGES) {
            return null;
        }
        if (answering) {
            return new DialogueScreen.Panel(name, lines, false, answers(), selected, portraitFont);
        }
        final boolean waits = typedAt >= 0 && step.answers().isEmpty() && ((age - typedAt) / BLINK) % 2 == 0;
        return new DialogueScreen.Panel(name, Typing.shownLines(lines, shown), waits, List.of(), 0, portraitFont);
    }

    private List<Hint> hints() {
        if (!listening() || bars < DialogueScreen.STAGES) {
            return List.of();
        }
        if (answering) {
            return List.of(new Hint(Mark.CHOOSE, labels.choose()), new Hint(Mark.ANSWER, labels.answer()));
        }
        final String menu = step.menu();
        return List.of(new Hint(Mark.NEXT, menu == null ? labels.next() : labels.menu(menu)));
    }

    private DialogueScreen.@Nullable Switch toggle() {
        if (!listening() || bars < DialogueScreen.STAGES || !step.handsOff()) {
            return null;
        }
        return new DialogueScreen.Switch(labels.dontShowAgain(), ticked);
    }

    private List<String> answers() {
        return step.answers().stream()
                .limit(Dialogue.MOST_ANSWERS)
                .map(answer -> answer.text().in(locale))
                .toList();
    }

    private List<String> page(final Step shown) {
        final int room = shown.answers().isEmpty() ? DialogueScreen.SPEAK_LINES : DialogueScreen.QUESTION_LINES;
        return DialogueScreen.page(shown.text().in(locale), room);
    }

    private static long ticks(final long milliseconds) {
        return (milliseconds + TICK - 1) / TICK;
    }
}
