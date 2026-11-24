package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.DialogPanel;
import eu.nordtal.season.packrendering.ui.DialogTitle;
import eu.nordtal.season.packrendering.ui.Letters;
import eu.nordtal.season.packrendering.ui.Look;
import eu.nordtal.season.packrendering.ui.Pen;
import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.packrendering.ui.Tooltip;
import eu.nordtal.season.packrendering.wheel.WheelTimetable;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.UseCooldown;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The wooden wheel as a dialog of 2 item bodies: a lone base art body, then glinting prizes with a 17-line description.
 * The description's focus cover hides every client focus outline while carrying the Spin plate and prize hovers.
 */
final class WheelDialog {

    /** @param spins the spins left after this one */
    record Window(@Example("3") long spins) {}

    static final DocumentSpec<Window> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "wheel",
                    "Menus",
                    Window.class,
                    1,
                    Slot.label("spins-left"),
                    Slot.label("no-spins-left"),
                    Slot.label("spin-button"),
                    Slot.label("spin-title"),
                    Slot.label("spin-cost"),
                    Slot.label("spin-none"),
                    Slot.label("close"))
            .titled("Wheel of Fortune");

    /** The item group whose cooldown plays the spin. */
    static final Key GROUP = Key.key("nordtal", "wheel");

    static final int BODIES = 2;
    private static final int BASE_BODY = 0;
    private static final int FOIL_BODY = 1;

    /** The panel's top, which the header draws from. */
    private static final int TOP = 43;

    /** The panel's one margin around the header, the wheel and the plate. */
    private static final int MARGIN = 7;

    private static final int PLATE_WIDTH = 75;
    private static final int PLATE_HEIGHT = BodyLine.PLATE;

    /** The text box's padding on each side, which the client keeps clear inside a body's text width. */
    private static final int PADDING = 4;

    /** The gap the client leaves between a body's item and its text. */
    private static final int ITEM_GAP = 2;

    /** One text line's height in pixels. */
    private static final int LINE_HEIGHT = 9;

    /** A hover glyph's ink, which stands over a 16-pixel prize. */
    private static final int HOVER = 16;

    /** The hover glyphs' ui art font, which with their 2 higher ascent puts their tops on the text box's top. */
    private static final int HOVER_SHIFT = -2;

    /** The spins line's offset below the plate's top. */
    private static final int SPINS_OFFSET = 22;

    private WheelDialog() {}

    /** The words on the wheel, read from {@link #DOCUMENT} in the player's language. */
    record Texts(
            Component heading,
            String name,
            String spins,
            String button,
            String title,
            String cost,
            String none,
            String close) {}

    /**
     * The dialog of {@code plan}, whose {@code prizes} stand on its two bodies.
     * {@code resting} holds the item at each place at rest, which the hovers show, and is null while it turns.
     * {@code click} spins, null without spins left, and {@code close} runs on Esc and on the close button.
     */
    static Dialog of(
            final WheelPlan plan,
            final List<ItemStack> prizes,
            final @Nullable List<ItemStack> resting,
            final WheelTimetable.Rows rows,
            final Texts texts,
            final boolean spinLeft,
            final @Nullable ClickEvent click,
            final DialogAction close) {
        final List<Boolean> foil = prizes.stream().map(WheelDialog::glints).toList();
        final ItemStack paper = named(texts.heading());
        final ItemStack shining = paper.clone();
        shining.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        final List<DialogBody> bodies = List.of(
                lone(stack(paper, "base", plan.base(foil)), rows),
                DialogBody.item(stack(shining, "foil", plan.foil(foil)))
                        .showDecorations(false)
                        .showTooltip(false)
                        .width(rows.itemWidth())
                        .height(rows.heights().get(FOIL_BODY))
                        .description(DialogBody.plainMessage(
                                description(texts, spinLeft, click, rows, resting), textWidth(rows)))
                        .build());
        final Component title = DialogTitle.of(DialogPanel.WHEEL_197, TOP)
                .header(Symbol.WHEEL, texts.name(), List.of(), MARGIN)
                .build();
        return Dialog.create(builder -> builder.empty()
                .base(Dialogs.base(title).body(bodies).build())
                .type(Dialogs.bodyOnly(BodyLine.normalButton(texts.close()), BodyLine.NATIVE_BUTTON, close)));
    }

    /**
     * The 17-line description: focus cover on line 0, all 12 prize hovers at rest, the Spin plate and the spins line.
     * While turning ({@code resting == null}), the Spin plate stays disabled without a click callback or hover tooltip.
     */
    static Component description(
            final Texts texts,
            final boolean spinLeft,
            final @Nullable ClickEvent click,
            final WheelTimetable.Rows rows,
            final @Nullable List<ItemStack> resting) {
        final boolean active = spinLeft && resting != null;
        final @Nullable ClickEvent activeClick = active ? click : null;
        final @Nullable Component hover = resting == null
                ? null
                : (active ? Tooltip.hover(texts.title(), texts.cost(), null) : Tooltip.hover(texts.none(), null, null));
        final int plateLine = rows.plateY() / LINE_HEIGHT;
        final int spinsY = rows.plateY() + SPINS_OFFSET;
        final int spinsLine = spinsY / LINE_HEIGHT;
        final String spins = Letters.cut(texts.spins(), PLATE_WIDTH);
        final List<Component> parts = new ArrayList<>(rows.lines() * 2);
        for (int line = 0; line < rows.lines(); line++) {
            if (line > 0) {
                parts.add(Component.newline());
            }
            parts.add(lineOf(
                    line, plateLine, spinsLine, spinsY, spins, texts, active, activeClick, hover, rows, resting));
        }
        return Component.textOfChildren(parts.toArray(Component[]::new));
    }

    private static Component lineOf(
            final int line,
            final int plateLine,
            final int spinsLine,
            final int spinsY,
            final String spins,
            final Texts texts,
            final boolean active,
            final @Nullable ClickEvent click,
            final @Nullable Component hover,
            final WheelTimetable.Rows rows,
            final @Nullable List<ItemStack> resting) {
        final Pen pen = Pen.start();
        if (line == 0) {
            final int leftWidth = rows.itemWidth() + ITEM_GAP + textWidth(rows) / 2;
            final int rightWidth = rows.rowWidth() - leftWidth;
            return pen.moveTo(-leftWidth)
                    .art(Glyphs.FONT_UI_RING, Glyphs.UI_WHEEL_RING_LEFT, leftWidth)
                    .art(Glyphs.FONT_UI_RING, Glyphs.UI_WHEEL_RING_RIGHT, rightWidth)
                    .build(0);
        }
        final int lineTop = line * LINE_HEIGHT;
        final int lineBottom = lineTop + LINE_HEIGHT - 1;
        hovers(pen, rows, lineTop, lineBottom, resting);
        if (line == plateLine) {
            final Component plate = BodyLine.start(rows.plateY() - lineTop)
                    .button(
                            new BodyLine.Press(click, hover),
                            button -> button.plate(
                                    PLATE_WIDTH, null, texts.button(), active ? Look.PRIMARY : Look.DISABLED))
                    .build();
            pen.moveTo(rows.plateX()).append(plate, PLATE_WIDTH);
        } else if (lineTop <= rows.plateY() + PLATE_HEIGHT - 1 && lineBottom >= rows.plateY()) {
            pen.moveTo(rows.plateX()).append(plateHit(click, hover), PLATE_WIDTH);
        }
        if (line == spinsLine) {
            pen.moveTo(rows.plateX() + (PLATE_WIDTH - Letters.visibleWidth(spins) + 1) / 2)
                    .text(spinsY - lineTop, spins, Colours.SOFT);
        }
        return pen.build(lineWidth(rows));
    }

    /** Transparent hit area for the lower text lines covered by the Spin plate. */
    private static Component plateHit(final @Nullable ClickEvent click, final @Nullable Component hover) {
        final Pen hit = Pen.start();
        final String font = Pen.artFont(HOVER_SHIFT);
        for (int x = 0; x + HOVER < PLATE_WIDTH; x += HOVER) {
            hit.moveTo(x).art(font, Glyphs.UI_HOVER_17, HOVER);
        }
        hit.moveTo(PLATE_WIDTH - HOVER).art(font, Glyphs.UI_HOVER_17, HOVER);
        Component area = hit.build(PLATE_WIDTH);
        if (hover != null) {
            area = area.hoverEvent(HoverEvent.showText(hover));
        }
        return click != null ? area.clickEvent(click) : area;
    }

    /** Draws the hovers whose 16 px prize box overlaps [{@code lineTop}, {@code lineBottom}]. */
    private static void hovers(
            final Pen pen,
            final WheelTimetable.Rows rows,
            final int lineTop,
            final int lineBottom,
            final @Nullable List<ItemStack> resting) {
        if (resting == null) {
            return;
        }
        final String font = Pen.artFont(HOVER_SHIFT);
        rows.hovers().entrySet().stream()
                .filter(entry ->
                        entry.getValue().y() <= lineBottom && entry.getValue().y() + HOVER - 1 >= lineTop)
                .sorted(Comparator.comparingInt(entry -> entry.getValue().x()))
                .forEach(entry -> pen.moveTo(entry.getValue().x())
                        .art(
                                font,
                                Glyphs.UI_HOVER_17,
                                HOVER,
                                resting.get(entry.getKey()).asHoverEvent()));
    }

    /** The lone base art body, without a text. */
    private static DialogBody lone(final ItemStack stack, final WheelTimetable.Rows rows) {
        return DialogBody.item(stack)
                .showDecorations(false)
                .showTooltip(false)
                .width(rows.loneItem())
                .height(rows.heights().get(BASE_BODY))
                .build();
    }

    /** The width the client gives the description text, its padding included. */
    private static int textWidth(final WheelTimetable.Rows rows) {
        return rows.rowWidth() - rows.itemWidth() - ITEM_GAP;
    }

    /**
     * The width every line of the description is drawn to: all of its box inside the padding.
     * The client's centring then moves it by nothing, and the hovers stand where the pack measured them.
     */
    private static int lineWidth(final WheelTimetable.Rows rows) {
        return textWidth(rows) - 2 * PADDING;
    }

    /** Whether {@code item} glints: by its override, the default its item has included, else by its enchantments. */
    static boolean glints(final ItemStack item) {
        final var stored = item.getData(DataComponentTypes.STORED_ENCHANTMENTS);
        return glints(
                item.getData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE),
                !item.getEnchantments().isEmpty(),
                stored != null && !stored.enchantments().isEmpty());
    }

    static boolean glints(
            final @Nullable Boolean override, final boolean hasEnchantments, final boolean hasStoredEnchantments) {
        return override != null ? override : (hasEnchantments || hasStoredEnchantments);
    }

    /** A paper named {@code name}, so the narrator reads the wheel's name instead of the paper's. */
    private static ItemStack named(final Component name) {
        final ItemStack stack = new ItemStack(Material.PAPER);
        stack.setData(DataComponentTypes.ITEM_NAME, name);
        return stack;
    }

    /** One of {@code item}, drawn by the wheel model {@code model} from {@code strings}, in the wheel's group. */
    static ItemStack stack(final ItemStack item, final String model, final List<String> strings) {
        final ItemStack stack = item.asOne();
        stack.setData(DataComponentTypes.ITEM_MODEL, Key.key("nordtal", "wheel/" + model));
        stack.setData(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                CustomModelData.customModelData().addStrings(strings).build());
        stack.setData(
                DataComponentTypes.USE_COOLDOWN,
                UseCooldown.useCooldown(1).cooldownGroup(GROUP).build());
        return stack;
    }
}
