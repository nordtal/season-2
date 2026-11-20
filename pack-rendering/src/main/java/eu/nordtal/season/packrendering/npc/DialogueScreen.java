package eu.nordtal.season.packrendering.npc;

import eu.nordtal.season.messagerendering.text.DialogueRoom;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.ui.Letters;
import eu.nordtal.season.packrendering.ui.Pen;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/**
 * The NPC dialogue's screen: one action-bar line with the band, the grey panel and the hints below it.
 * x counts from the panel's left edge; a piece whose top stands b rows above the screen's bottom takes shifted font
 * 72 minus b. The band grows over {@link #STAGES} stages; the panel and the hints show only at the last.
 */
public final class DialogueScreen {

    /** The stages of the band's opening; 0 draws no band. */
    public static final int STAGES = 4;

    /** The columns a line of the text takes, so it keeps clear of the mark column. */
    public static final int BODY_ROOM = DialogueRoom.BODY;

    /** The columns the speaker's name takes, in bold. */
    public static final int NAME_ROOM = DialogueRoom.NAME;

    /** The columns an answer's label takes in bold, so it keeps clear of its number. */
    public static final int LABEL_ROOM = DialogueRoom.LABEL;

    /** The lines of text on the panel while the NPC speaks. */
    public static final int SPEAK_LINES = DialogueRoom.SPEAK_LINES;

    /** The lines of text above the answers. */
    public static final int QUESTION_LINES = DialogueRoom.QUESTION_LINES;

    /** The most answers a question shows. */
    public static final int ANSWERS = 3;

    /** The columns the last line of a page that others follow leaves free for the page count. */
    public static final int COUNT_ROOM = DialogueRoom.COUNT;

    /** The name tab's colour when the NPC names none. */
    public static final TextColor DEFAULT_TAB = TextColor.color(0x345AB4);

    static final int WIDTH = 300;
    static final int TEXT_X = 60;
    static final int PORTRAIT_X = 7;
    static final int TAB_X = 56;
    static final int STRIP_X = 56;
    static final int CHEVRON_X = 60;
    static final int LABEL_X = 68;
    static final int NUMBER_END = 291;
    static final int MORE_X = 288;
    static final int COUNT_END = 284;
    static final int HINT_END = 298;
    static final int CHECK_X = 2;

    static final TextColor INK = TextColor.color(0x2B2B2B);
    static final TextColor SOFT = TextColor.color(0x404040);
    static final TextColor FAINT = TextColor.color(0x505050);
    static final TextColor ACCENT = TextColor.color(0x284894);
    static final TextColor HINT = TextColor.color(0xD8D8D8);

    private static final int BOTTOM = 16;
    private static final int TILE = 256;
    private static final int TILES = 12;
    private static final int BAND = TILE * TILES;
    private static final int PORTRAIT = 46;
    private static final int STRIP = 239;
    private static final int CHEVRON = 4;
    private static final int MARK = 7;
    private static final int MORE = 5;
    private static final int KEY_SHIFT = 22;
    private static final int TAB_PADDING = 6;
    private static final int ICON = 7;
    private static final int ICON_ADVANCE = 8;
    private static final int ICON_GAP = 4;
    private static final int HINT_SHIFT = 62;
    private static final int COUNT_SHIFT = 43;
    private static final int MARK_TO_WORD = 3;
    private static final int BETWEEN_HINTS = 9;
    private static final int SWITCH_GAP = 4;

    /** The panel heights in the order of the pack's panels and portraits. */
    private static final List<Integer> HEIGHTS = List.of(43, 48, 53, 55, 58, 63, 65, 67, 75, 77, 87);

    private DialogueScreen() {}

    /** What a hint's mark asks for. */
    public enum Mark {
        /** A right-click: on to the next text or page. */
        NEXT(Glyphs.NPC_MOUSE_RIGHT),
        /** A left-click: take the selected answer. */
        ANSWER(Glyphs.NPC_MOUSE_LEFT),
        /** The wheel: move the selection. */
        CHOOSE(Glyphs.NPC_MOUSE_WHEEL);

        private final String glyph;

        Mark(final String glyph) {
            this.glyph = glyph;
        }
    }

    /** The symbols a name tab can show before the name, in the order of the pack's tab icons. */
    public enum Icon {
        STAR(0),
        PIN(1),
        RAIL(2),
        MEDAL(3),
        FLAG(4),
        WHEEL(5),
        SWORDS(6),
        SHIELD(7),
        HAND_IN(8),
        CHEST(9),
        EYE(10),
        PICKAXE(11),
        BOX(12),
        HEART(13);

        /** The icon's place among the pack's tab icons. */
        private final int index;

        Icon(final int index) {
            this.index = index;
        }

        String glyph() {
            return Glyphs.NPC_ICONS.get(index);
        }
    }

    /** An input hint below the panel: its mark, then its word. */
    public record Hint(Mark mark, String word) {}

    /** The switch below the panel's left end: its box, its word and the Shift key cap. */
    public record Switch(String label, boolean ticked) {}

    /**
     * Who speaks: the name on the tab, the tab's colour, the symbol before the name and the font of the portrait.
     */
    public record Speaker(
            String name, TextColor colour, @Nullable Icon icon, String portraitFont) {
        public Speaker {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(colour, "colour");
            Objects.requireNonNull(portraitFont, "portraitFont");
        }

        /** A speaker with the default blue tab, no symbol and the pack's default portrait. */
        public Speaker(final String name) {
            this(name, DEFAULT_TAB, null, Glyphs.FONT_NPC);
        }
    }

    /** Where a page stands among the pages of one text, counting from 1. */
    public record Page(int number, int count) {
        public Page {
            if (number < 1 || number > count) {
                throw new IllegalArgumentException("page " + number + " is not one of " + count);
            }
        }

        boolean last() {
            return number == count;
        }
    }

    /**
     * The panel: the speaker, the lines typed so far, and {@code rows} answer rows that keep their room while typing.
     * {@code answers} fill them once typed; {@code selected} counts from 0. {@code more} draws the mark that the text
     * waits, {@code page} the count on every page but the last.
     */
    public record Panel(
            Speaker speaker,
            List<String> lines,
            int rows,
            List<String> answers,
            int selected,
            boolean more,
            @Nullable Page page) {
        public Panel {
            Objects.requireNonNull(speaker, "speaker");
            lines = List.copyOf(lines);
            answers = List.copyOf(answers);
            if (rows < 0 || rows > ANSWERS) {
                throw new IllegalArgumentException("a panel holds 0 to " + ANSWERS + " answer rows, not " + rows);
            }
            if (!answers.isEmpty() && answers.size() != rows) {
                throw new IllegalArgumentException(answers.size() + " answers do not fill " + rows + " rows");
            }
            final int room = rows == 0 ? SPEAK_LINES : QUESTION_LINES;
            if (lines.size() > room) {
                throw new IllegalArgumentException("this panel holds " + room + " lines, not " + lines.size());
            }
            if (!answers.isEmpty() && (selected < 0 || selected >= answers.size())) {
                throw new IllegalArgumentException("answer " + selected + " is not one of " + answers.size());
            }
            if (page != null && !page.last() && rows > 0) {
                throw new IllegalArgumentException("a question's answers come with its last page");
            }
        }

        /** A speaking panel with no page count. */
        public static Panel speaking(final Speaker speaker, final List<String> lines, final boolean more) {
            return new Panel(speaker, lines, 0, List.of(), 0, more, null);
        }

        /** A question's panel with its answers shown. */
        public static Panel question(
                final Speaker speaker, final List<String> lines, final List<String> answers, final int selected) {
            return new Panel(speaker, lines, answers.size(), answers, selected, false, null);
        }

        int height() {
            if (rows == 0) {
                return lines.size() <= 3 ? 48 : 58;
            }
            return 26 + 10 * (Math.max(lines.size(), 1) - 1) + 12 * (rows - 1) + 17;
        }
    }

    /**
     * The action-bar line at {@code stage}, which always advances the panel's width, so the client keeps it in place.
     * It draws the band and, at the last stage, the panel, the hints right-aligned below it with the main action last,
     * and the switch on the left.
     */
    public static Component bottom(
            final int stage, final @Nullable Panel panel, final List<Hint> hints, final @Nullable Switch toggle) {
        if (stage < 0 || stage > STAGES) {
            throw new IllegalArgumentException("a stage is 0 to " + STAGES + ", not " + stage);
        }
        final Pen pen = Pen.start();
        if (stage > 0) {
            pen.moveTo(-(BAND - WIDTH) / 2);
            for (int tile = 0; tile < TILES; tile++) {
                pen.art(Glyphs.FONT_NPC, Glyphs.NPC_BANDS.get(stage - 1), TILE);
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

    /** Breaks {@code text} at spaces into lines inking at most {@code room} columns, in a word only if it is wider. */
    public static List<String> wrap(final String text, final int room) {
        return Letters.wrapVisible(text, room);
    }

    /** Returns {@code text}, or its start ending in an ellipsis where it inks more than {@code room} columns. */
    public static String cut(final String text, final int room) {
        return Letters.cut(text, room);
    }

    /** Returns {@code text}, or its start and an ellipsis where it inks more than {@code room} columns in bold. */
    public static String boldCut(final String text, final int room) {
        return Letters.boldCut(text, room);
    }

    /** Breaks {@code text} into pages as {@link DialogueRoom#pages} does, the last holding {@code lastHolds} lines. */
    public static List<List<String>> pages(final String text, final int lastHolds) {
        return DialogueRoom.pages(text, lastHolds);
    }

    private static void panel(final Pen pen, final Panel panel) {
        final int height = panel.height();
        final int index = HEIGHTS.indexOf(height);
        final int top = BOTTOM + height;
        pen.moveTo(0)
                .art(Glyphs.FONT_NPC, Glyphs.NPC_PANELS_LEFT.get(index), TILE)
                .art(Glyphs.FONT_NPC, Glyphs.NPC_PANELS_RIGHT.get(index), WIDTH - TILE);
        pen.moveTo(PORTRAIT_X).art(panel.speaker().portraitFont(), Glyphs.NPC_PORTRAITS.get(index), PORTRAIT);
        tab(pen, panel.speaker(), top);
        for (int line = 0; line < panel.lines().size(); line++) {
            final int b = top - 13 - 10 * line;
            pen.moveTo(TEXT_X).text(text(72 - b), panel.lines().get(line), INK);
        }
        for (int answer = 0; answer < panel.answers().size(); answer++) {
            answer(pen, panel, answer);
        }
        if (panel.page() != null && !panel.page().last()) {
            final String count = panel.page().number() + "/" + panel.page().count();
            pen.moveTo(COUNT_END - SmallFont.visibleWidth(count))
                    .draw(small(COUNT_SHIFT), count, FAINT, SmallFont.width(count));
        }
        if (panel.more()) {
            pen.moveTo(MORE_X).art(Glyphs.FONT_NPC, Glyphs.NPC_MORE, MORE);
        }
    }

    private static void tab(final Pen pen, final Speaker speaker, final int top) {
        final String name = boldCut(speaker.name(), NAME_ROOM);
        final int icon = speaker.icon() == null ? 0 : ICON + ICON_GAP;
        final int width = TAB_PADDING + icon + Letters.boldVisibleWidth(name) + TAB_PADDING;
        final String art = art(65 - top);
        final TextColor edge = darker(speaker.colour(), 0.45);
        pen.moveTo(TAB_X).art(art, Glyphs.NPC_TAB_END, 1, edge);
        pen.runs(art, Glyphs.NPC_TAB_EDGE, width - 2, edge);
        pen.art(art, Glyphs.NPC_TAB_END, 1, edge);
        pen.moveTo(TAB_X + 1).runs(art, Glyphs.NPC_TAB_BODY, width - 2, speaker.colour());
        pen.moveTo(TAB_X + 1).runs(art, Glyphs.NPC_TAB_LIGHT, width - 2, lighter(speaker.colour(), 0.25));
        final String text = text(68 - top);
        if (speaker.icon() != null) {
            pen.moveTo(TAB_X + TAB_PADDING).draw(text, speaker.icon().glyph(), NamedTextColor.WHITE, ICON_ADVANCE);
        }
        pen.moveTo(TAB_X + TAB_PADDING + icon).bold(text, name, NamedTextColor.WHITE);
    }

    private static void answer(final Pen pen, final Panel panel, final int answer) {
        final int row = panel.answers().size() - 1 - answer;
        final int top = 33 + 12 * row;
        final boolean on = answer == panel.selected();
        final String label = boldCut(panel.answers().get(answer), LABEL_ROOM);
        if (on) {
            pen.moveTo(STRIP_X).art(art(72 - top), Glyphs.NPC_STRIP, STRIP);
            pen.moveTo(CHEVRON_X).art(art(72 - (top - 2)), Glyphs.NPC_CHEVRON, CHEVRON);
            pen.moveTo(LABEL_X).bold(text(72 - (top - 2)), label, ACCENT);
        } else {
            pen.moveTo(LABEL_X).text(text(72 - (top - 2)), label, SOFT);
        }
        final String number = String.valueOf(answer + 1);
        pen.moveTo(NUMBER_END - SmallFont.visibleWidth(number))
                .draw(small(72 - (top - 3)), number, on ? ACCENT : FAINT, SmallFont.width(number));
    }

    private static void hints(final Pen pen, final List<Hint> hints) {
        int x = HINT_END;
        for (final Hint hint : hints.reversed()) {
            final String word = SmallFont.fold(hint.word());
            x -= SmallFont.visibleWidth(word);
            pen.moveTo(x).draw(small(HINT_SHIFT), word, HINT, SmallFont.width(word));
            x -= MARK_TO_WORD + MARK;
            pen.moveTo(x).art(Glyphs.FONT_NPC, hint.mark().glyph, MARK);
            x -= BETWEEN_HINTS;
        }
    }

    private static void toggle(final Pen pen, final Switch toggle) {
        final String word = SmallFont.fold(toggle.label());
        pen.moveTo(CHECK_X).art(Glyphs.FONT_NPC, toggle.ticked() ? Glyphs.NPC_CHECK_ON : Glyphs.NPC_CHECK_OFF, MARK);
        final int wordX = CHECK_X + MARK + SWITCH_GAP;
        pen.moveTo(wordX).draw(small(HINT_SHIFT), word, HINT, SmallFont.width(word));
        pen.moveTo(wordX + SmallFont.visibleWidth(word) + SWITCH_GAP)
                .art(Glyphs.FONT_NPC, Glyphs.NPC_KEY_SHIFT, KEY_SHIFT);
    }

    /** Returns {@code colour} with every channel times {@code factor}. */
    static TextColor darker(final TextColor colour, final double factor) {
        return TextColor.color((int) Math.round(colour.red() * factor), (int) Math.round(colour.green() * factor), (int)
                Math.round(colour.blue() * factor));
    }

    /** Returns {@code colour} moved {@code factor} of the way to white. */
    static TextColor lighter(final TextColor colour, final double factor) {
        return TextColor.color(
                (int) Math.round(colour.red() + (255 - colour.red()) * factor),
                (int) Math.round(colour.green() + (255 - colour.green()) * factor),
                (int) Math.round(colour.blue() + (255 - colour.blue()) * factor));
    }

    private static String text(final int shift) {
        return font(Glyphs.FONT_NPC_TEXT, shift, "text");
    }

    private static String art(final int shift) {
        return font(Glyphs.FONT_NPC_ART, shift, "art");
    }

    private static String small(final int shift) {
        return font(Glyphs.FONT_NPC_SMALL, shift, "small");
    }

    private static String font(final Map<Integer, String> fonts, final int shift, final String what) {
        final String font = fonts.get(shift);
        if (font == null) {
            throw new IllegalStateException("glyphs.json lists no npc " + what + " font " + shift + " px low");
        }
        return font;
    }
}
