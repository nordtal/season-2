package eu.nordtal.season.packrendering.npc;

import eu.nordtal.season.messagerendering.text.DialogueRoom;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.Letters;
import eu.nordtal.season.packrendering.ui.Look;
import eu.nordtal.season.packrendering.ui.Pen;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import org.jspecify.annotations.Nullable;

/**
 * The NPC dialogue's screen: one action-bar line with the bottom bar, the panel and the hints, and the top bar.
 * The client centres the action bar, so the line advances by the panel's width and x counts from the panel's left edge.
 * The bars grow over {@link #STAGES} stages; the panel and the hints show only at the last.
 */
public final class DialogueScreen {

    /** The stages of the bars' opening; 0 draws no bar. */
    public static final int STAGES = 4;

    /** The columns a line of the text takes, so it keeps clear of the hold mark. */
    public static final int BODY_ROOM = DialogueRoom.BODY;

    /** The columns the speaker's name takes. */
    public static final int NAME_ROOM = DialogueRoom.NAME;

    /** The columns an answer's label takes, so it keeps clear of its number. */
    public static final int LABEL_ROOM = DialogueRoom.LABEL;

    /** The lines of text on the panel while the NPC speaks. */
    public static final int SPEAK_LINES = DialogueRoom.SPEAK_LINES;

    /** The lines of text above the answers. */
    public static final int QUESTION_LINES = DialogueRoom.QUESTION_LINES;

    private static final int WIDTH = 262;
    private static final int MARGIN = 7;
    private static final int TEXT_X = 36;
    private static final int PORTRAIT = 24;
    private static final int TILE = 256;
    private static final int TILES = 12;
    private static final int BAND = TILE * TILES;
    private static final int PLATE_WIDTH = 248;
    private static final int PLATE_LABEL = 4;
    private static final int HOLD = 5;
    private static final int HINT_SHIFT = 53;
    private static final int HINT_GAP = 4;
    private static final int HINT_BETWEEN = 10;
    private static final int KEY_SHIFT = 30;
    private static final int CHECK = 9;

    private static final List<String> BARS =
            List.of(Glyphs.NPC_BAR_1, Glyphs.NPC_BAR_2, Glyphs.NPC_BAR_3, Glyphs.NPC_BAR_4);
    private static final List<String> TOP_BARS =
            List.of(Glyphs.NPC_TOP_BAR_1, Glyphs.NPC_TOP_BAR_2, Glyphs.NPC_TOP_BAR_3, Glyphs.NPC_TOP_BAR_4);

    private DialogueScreen() {}

    /** What a hint's mark asks for. */
    public enum Mark {
        /** A right-click: on to the next text. */
        NEXT(Glyphs.NPC_MOUSE_RIGHT, 9, 9),
        /** A left-click: take the selected answer. */
        ANSWER(Glyphs.NPC_MOUSE_LEFT, 9, 9),
        /** The wheel: move the selection. */
        CHOOSE(Glyphs.NPC_MOUSE_WHEEL, 14, 15);

        private final String glyph;
        private final int ink;
        private final int box;

        Mark(final String glyph, final int ink, final int box) {
            this.glyph = glyph;
            this.ink = ink;
            this.box = box;
        }
    }

    /** An input hint on the bottom bar: its mark, then its word. */
    public record Hint(Mark mark, String word) {}

    /**
     * The panel: the speaker's name, the lines of text as they are typed, and the answers.
     * No answer is the speaking panel, one stands on the first plate of the panel for two. {@code selected} counts
     * from 0 and is read only with answers; {@code holdMark} shows that the text waits, on the speaking panel only.
     */
    public record Panel(
            String name,
            List<String> lines,
            boolean holdMark,
            List<String> answers,
            int selected,
            String portraitFont) {
        public Panel {
            lines = List.copyOf(lines);
            answers = List.copyOf(answers);
            Objects.requireNonNull(portraitFont, "portraitFont");
            if (answers.size() > 3) {
                throw new IllegalArgumentException("a panel holds at most 3 answers, not " + answers.size());
            }
            final int room = answers.isEmpty() ? SPEAK_LINES : QUESTION_LINES;
            if (lines.size() > room) {
                throw new IllegalArgumentException("this panel holds " + room + " lines, not " + lines.size());
            }
            if (!answers.isEmpty() && (selected < 0 || selected >= answers.size())) {
                throw new IllegalArgumentException("answer " + selected + " is not one of " + answers.size());
            }
            if (holdMark && !answers.isEmpty()) {
                throw new IllegalArgumentException("only the speaking panel draws the hold mark");
            }
        }

        /** Creates a panel with the pack's default portrait. */
        public Panel(
                final String name,
                final List<String> lines,
                final boolean holdMark,
                final List<String> answers,
                final int selected) {
            this(name, lines, holdMark, answers, selected, Glyphs.FONT_NPC);
        }
    }

    /** The switch on the bar's left end: the Shift key cap, its box and its label. */
    public record Switch(String label, boolean ticked) {}

    /**
     * The action-bar line at {@code stage}, which always advances 262, so the client keeps it in place.
     * It draws the bottom bar and, at the last stage, the panel, the hints right-aligned on the bar with the main
     * action last, and the switch.
     */
    public static Component bottom(
            final int stage, final @Nullable Panel panel, final List<Hint> hints, final @Nullable Switch toggle) {
        stage(stage);
        final Pen pen = Pen.start();
        if (stage > 0) {
            pen.moveTo(-(BAND - WIDTH) / 2);
            for (int tile = 0; tile < TILES; tile++) {
                pen.art(Glyphs.FONT_NPC, BARS.get(stage - 1), TILE);
            }
        }
        if (stage == STAGES) {
            if (panel != null) {
                panel(pen, panel);
            }
            hints(pen, hints);
            if (toggle != null) {
                toggle(pen, toggle);
            }
        }
        return pen.build(WIDTH).shadowColor(ShadowColor.none());
    }

    /** The top bar at {@code stage}, as a boss bar's name 3072 wide; empty at stage 0. */
    public static Component top(final int stage) {
        stage(stage);
        if (stage == 0) {
            return Component.empty();
        }
        final Pen pen = Pen.start();
        for (int tile = 0; tile < TILES; tile++) {
            pen.art(Glyphs.FONT_NPC, TOP_BARS.get(stage - 1), TILE);
        }
        return pen.build(BAND).shadowColor(ShadowColor.none());
    }

    /** Breaks {@code text} at spaces into lines inking at most {@code room} columns, in a word only if it is wider. */
    public static List<String> wrap(final String text, final int room) {
        return Letters.wrapVisible(text, room);
    }

    /** Returns {@code text}, or its start ending in an ellipsis where it inks more than {@code room} columns. */
    public static String cut(final String text, final int room) {
        return Letters.cut(text, room);
    }

    /** The first {@code lines} lines of {@code text} in the body's room, the last ending in an ellipsis. */
    public static List<String> page(final String text, final int lines) {
        final List<String> rows = wrap(text, BODY_ROOM);
        if (rows.size() <= lines) {
            return rows;
        }
        String last = rows.get(lines - 1);
        while (Letters.visibleWidth(last + Letters.ELLIPSIS) > BODY_ROOM) {
            final String kept = last.stripTrailing();
            final int space = kept.lastIndexOf(' ');
            last = space > 0 ? kept.substring(0, space) : kept.substring(0, kept.offsetByCodePoints(kept.length(), -1));
        }
        final List<String> page = new ArrayList<>(rows.subList(0, lines - 1));
        page.add(last.stripTrailing() + Letters.ELLIPSIS);
        return List.copyOf(page);
    }

    private static void panel(final Pen pen, final Panel panel) {
        final Shape shape = Shape.of(panel.answers().size());
        pen.moveTo(0).art(Glyphs.FONT_NPC, shape.left, TILE).art(Glyphs.FONT_NPC, shape.right, WIDTH - TILE);
        pen.moveTo(MARGIN).art(panel.portraitFont(), shape.portrait, PORTRAIT);
        pen.moveTo(TEXT_X).text(text(shape.name), cut(panel.name(), NAME_ROOM), Colours.INK);
        for (int line = 0; line < panel.lines().size(); line++) {
            pen.moveTo(TEXT_X)
                    .text(text(shape.lines.get(line)), cut(panel.lines().get(line), BODY_ROOM), Colours.INK);
        }
        for (int answer = 0; answer < panel.answers().size(); answer++) {
            final Plate plate = shape.plates.get(answer);
            final boolean on = answer == panel.selected();
            final Look look = on ? Look.PRIMARY : Look.NORMAL;
            final String number = String.valueOf(answer + 1);
            final String font = text(plate.label);
            pen.moveTo(MARGIN).art(Glyphs.FONT_NPC, on ? plate.on : plate.normal, PLATE_WIDTH);
            pen.moveTo(MARGIN + PLATE_LABEL).text(font, cut(panel.answers().get(answer), LABEL_ROOM), look.ink());
            pen.moveTo(MARGIN + PLATE_WIDTH - PLATE_LABEL - Letters.visibleWidth(number))
                    .text(font, number, look.soft());
        }
        if (panel.holdMark()) {
            pen.moveTo(WIDTH - MARGIN - HOLD).art(Glyphs.FONT_NPC, Glyphs.NPC_HOLD, HOLD);
        }
    }

    private static void hints(final Pen pen, final List<Hint> hints) {
        int x = WIDTH - MARGIN;
        for (final Hint hint : hints.reversed()) {
            x -= Letters.visibleWidth(hint.word());
            pen.moveTo(x).text(text(HINT_SHIFT), hint.word(), Colours.TOOLTIP_TITLE);
            x -= HINT_GAP + hint.mark().box;
            pen.moveTo(x).art(Glyphs.FONT_NPC, hint.mark().glyph, hint.mark().ink);
            x -= HINT_BETWEEN;
        }
    }

    private static void toggle(final Pen pen, final Switch toggle) {
        pen.moveTo(MARGIN).art(Glyphs.FONT_NPC, Glyphs.NPC_KEY_SHIFT, KEY_SHIFT);
        pen.moveTo(MARGIN + KEY_SHIFT + HINT_GAP)
                .art(Glyphs.FONT_NPC, toggle.ticked() ? Glyphs.NPC_CHECK_ON : Glyphs.NPC_CHECK_OFF, CHECK);
        pen.moveTo(MARGIN + KEY_SHIFT + HINT_GAP + CHECK + HINT_GAP)
                .text(text(HINT_SHIFT), toggle.label(), Colours.TOOLTIP_TITLE);
    }

    private static String text(final int shift) {
        final String font = Glyphs.FONT_NPC_TEXT.get(shift);
        if (font == null) {
            throw new IllegalStateException("glyphs.json lists no npc text font " + shift + " px low");
        }
        return font;
    }

    private static void stage(final int stage) {
        if (stage < 0 || stage > STAGES) {
            throw new IllegalArgumentException("a stage is 0 to " + STAGES + ", not " + stage);
        }
    }

    /** An answer row: its plates and the shift of its label. */
    private record Plate(String normal, String on, int label) {
        static final Plate FIRST = new Plate(Glyphs.NPC_PLATE_1, Glyphs.NPC_PLATE_ON_1, -21);
        static final Plate SECOND = new Plate(Glyphs.NPC_PLATE_2, Glyphs.NPC_PLATE_ON_2, -1);
        static final Plate THIRD = new Plate(Glyphs.NPC_PLATE_3, Glyphs.NPC_PLATE_ON_3, 19);
    }

    /** A panel's art and the shifts of its name, its lines and its plates, which follow from its height. */
    private enum Shape {
        SPEAK(
                Glyphs.NPC_PANEL_SPEAK_0,
                Glyphs.NPC_PANEL_SPEAK_1,
                Glyphs.NPC_PORTRAIT_SPEAK,
                -6,
                List.of(5, 14, 23),
                List.of()),
        TWO(
                Glyphs.NPC_PANEL_2_0,
                Glyphs.NPC_PANEL_2_1,
                Glyphs.NPC_PORTRAIT_2,
                -37,
                List.of(-26, -17),
                List.of(Plate.SECOND, Plate.THIRD)),
        THREE(
                Glyphs.NPC_PANEL_3_0,
                Glyphs.NPC_PANEL_3_1,
                Glyphs.NPC_PORTRAIT_3,
                -57,
                List.of(-46, -37),
                List.of(Plate.FIRST, Plate.SECOND, Plate.THIRD));

        private final String left;
        private final String right;
        private final String portrait;
        private final int name;
        // List.of is unmodifiable, which the checker cannot see.
        @SuppressWarnings("ImmutableEnumChecker")
        private final List<Integer> lines;

        @SuppressWarnings("ImmutableEnumChecker")
        private final List<Plate> plates;

        Shape(
                final String left,
                final String right,
                final String portrait,
                final int name,
                final List<Integer> lines,
                final List<Plate> plates) {
            this.left = left;
            this.right = right;
            this.portrait = portrait;
            this.name = name;
            this.lines = lines;
            this.plates = plates;
        }

        static Shape of(final int answers) {
            return switch (answers) {
                case 0 -> SPEAK;
                case 1, 2 -> TWO;
                default -> THREE;
            };
        }
    }
}
