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
import net.kyori.adventure.text.format.ShadowColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The wooden wheel as a dialog of 7 item bodies, which the pack draws over one another.
 * It turns them by the cooldown of {@link #GROUP}. Their texts carry the plate and, at rest, the prizes' hovers.
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
                    Slot.label("spin-none"))
            .titled("Wheel of Fortune");

    /** The item group whose cooldown plays the spin. */
    static final Key GROUP = Key.key("nordtal", "wheel");

    static final int BODIES = 7;
    private static final int DISC_BODY = 0;
    private static final int PRIZES_BODY = 1;
    private static final int FOIL_BODY = 2;
    private static final int PLATE_BODY = 3;
    private static final int OVERLAY_BODY = 4;
    private static final int HOVER_BODY = 5;
    private static final int LAST_BODY = 6;

    /** The panel's top, which the header draws from; the column below starts at the client's 63. */
    private static final int TOP = 43;

    /** The panel's one margin around the header, the wheel and the plate. */
    private static final int MARGIN = 7;

    private static final int PLATE_WIDTH = 75;

    /** The text box's padding on each side, which the client keeps clear inside a body's text width. */
    private static final int PADDING = 4;

    /** The gap the client leaves between a body's item and its text. */
    private static final int ITEM_GAP = 2;

    /** A text two lines high: 9 a line and the padding above and below. */
    private static final int TWO_LINES = 2 * 9 + 2 * PADDING;

    /** A hover glyph's ink, which stands over a 16-pixel prize. */
    private static final int HOVER = 16;

    /** The hover glyphs' ui art font, which puts their tops on the text box's top. */
    private static final int HOVER_SHIFT = -4;

    /**
     * The plate's top above its text row, as high as the pack draws art, so most of the plate is in its box.
     * The 17-pixel plate overhangs the one-line box of 17 by 2, where the client takes no click and no hover.
     */
    private static final int PLATE_UP = 2;

    /** The spins line's ui text font, below the plate's box and outside the client's outline. */
    private static final int SPINS_SHIFT = 22;

    private WheelDialog() {}

    /** The words on the wheel, read from {@link #DOCUMENT} in the player's language. */
    record Texts(Component heading, String name, String spins, String button, String title, String cost, String none) {}

    /**
     * The dialog of {@code plan}, whose {@code prizes} stand at its bodies.
     * {@code resting} holds the item at each place at rest, which the hovers show, and is null while it turns.
     * {@code click} spins, null without spins left, and {@code close} runs when the player closes it.
     */
    static Dialog of(
            final WheelPlan plan,
            final List<ItemStack> prizes,
            final @Nullable List<ItemStack> resting,
            final WheelTimetable.Rows rows,
            final Texts texts,
            final String language,
            final boolean spinLeft,
            final @Nullable ClickEvent click,
            final DialogAction close) {
        final List<Boolean> foil = prizes.stream().map(WheelDialog::glints).toList();
        final ItemStack paper = named(texts.heading());
        final ItemStack shining = paper.clone();
        shining.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        final List<DialogBody> bodies = new ArrayList<>(BODIES);
        bodies.add(lone(stack(paper, "disc", plan.disc()), rows, DISC_BODY));
        bodies.add(row(stack(paper, "prizes", plan.prizes(foil, false)), rows, PRIZES_BODY, resting));
        bodies.add(row(stack(shining, "prizes_foil", plan.prizes(foil, true)), rows, FOIL_BODY, resting));
        final ItemStack beside = resting == null ? paper : resting.get(rows.itemPlace());
        bodies.add(DialogBody.item(stack(beside, "none", List.of()))
                .showDecorations(false)
                .showTooltip(resting != null)
                .width(rows.plateItem())
                .height(rows.heights().get(PLATE_BODY))
                .description(DialogBody.plainMessage(
                        plateRow(texts, spinLeft, click, rows, resting), textWidth(rows, PLATE_BODY)))
                .build());
        bodies.add(row(stack(paper, "overlay", plan.overlay(language)), rows, OVERLAY_BODY, resting));
        bodies.add(row(stack(paper, "none", List.of()), rows, HOVER_BODY, resting));
        bodies.add(lone(stack(paper, "none", List.of()), rows, LAST_BODY));
        final Component title = DialogTitle.of(DialogPanel.WHEEL_201, TOP)
                .header(Symbol.WHEEL, texts.name(), List.of(), MARGIN)
                .build();
        return Dialog.create(builder ->
                builder.empty().base(Dialogs.base(title).body(bodies).build()).type(Dialogs.bodyOnly(close)));
    }

    /**
     * The plate row's text: the hovers on its line, the plate and under it the spins line.
     * The plate's hover says what a spin costs or that none is left. All share one line, so the row is one line high.
     */
    static Component plateRow(
            final Texts texts,
            final boolean spinLeft,
            final @Nullable ClickEvent click,
            final WheelTimetable.Rows rows,
            final @Nullable List<ItemStack> resting) {
        final Component hover =
                spinLeft ? Tooltip.hover(texts.title(), texts.cost(), null) : Tooltip.hover(texts.none(), null, null);
        final Component plate = BodyLine.start(-PLATE_UP)
                .button(
                        new BodyLine.Press(click, hover),
                        line -> line.plate(PLATE_WIDTH, null, texts.button(), spinLeft ? Look.PRIMARY : Look.DISABLED))
                .build();
        final String spins = Letters.cut(texts.spins(), PLATE_WIDTH);
        final int x = rows.plateX();
        return hovers(Pen.start(), rows, PLATE_BODY, resting, Glyphs.UI_HOVER_17)
                .moveTo(x)
                .append(plate, PLATE_WIDTH)
                .moveTo(x + (PLATE_WIDTH - Letters.visibleWidth(spins) + 1) / 2)
                .text(SPINS_SHIFT, spins, Colours.SOFT)
                .build(lineWidth(rows, PLATE_BODY));
    }

    /** A body without a text, which sets its row's height. */
    private static DialogBody lone(final ItemStack stack, final WheelTimetable.Rows rows, final int body) {
        return DialogBody.item(stack)
                .showDecorations(false)
                .showTooltip(false)
                .width(rows.loneItem())
                .height(rows.heights().get(body))
                .build();
    }

    /** A body whose text holds the hovers over the prizes on its row while the wheel rests. */
    private static DialogBody row(
            final ItemStack stack,
            final WheelTimetable.Rows rows,
            final int body,
            final @Nullable List<ItemStack> resting) {
        final int height = rows.heights().get(body);
        final boolean two = height == TWO_LINES;
        Component text = hovers(Pen.start(), rows, body, resting, two ? Glyphs.UI_HOVER_26 : Glyphs.UI_HOVER_17)
                .build(lineWidth(rows, body));
        if (two) {
            // The second line only fills the box, which the first line's tall hovers reach down over.
            text = Component.textOfChildren(
                    text, Component.newline(), Pen.start().build(lineWidth(rows, body)));
        }
        return DialogBody.item(stack)
                .showDecorations(false)
                .showTooltip(false)
                .width(rows.itemWidth())
                .height(height)
                .description(DialogBody.plainMessage(text, textWidth(rows, body)))
                .build();
    }

    /** Draws the hovers of {@code body}'s row with {@code glyph}, each showing the item resting under it. */
    private static Pen hovers(
            final Pen pen,
            final WheelTimetable.Rows rows,
            final int body,
            final @Nullable List<ItemStack> resting,
            final String glyph) {
        if (resting == null) {
            return pen;
        }
        final String font = Pen.artFont(HOVER_SHIFT);
        rows.hovers().entrySet().stream()
                .filter(hover -> hover.getValue().body() == body)
                .sorted(Comparator.comparingInt(hover -> hover.getValue().x()))
                .forEach(hover -> pen.moveTo(hover.getValue().x())
                        .append(
                                Component.text(glyph)
                                        .font(Key.key(font))
                                        .color(Colours.ART)
                                        .shadowColor(ShadowColor.none())
                                        .hoverEvent(resting.get(hover.getKey()).asHoverEvent()),
                                HOVER + 1)
                        .move(-1));
        return pen;
    }

    /** The width the client gives {@code body}'s text, its padding included. */
    private static int textWidth(final WheelTimetable.Rows rows, final int body) {
        return rows.rowWidth() - (body == PLATE_BODY ? rows.plateItem() : rows.itemWidth()) - ITEM_GAP;
    }

    /**
     * The width every line of {@code body}'s text is drawn to: all of its box inside the padding.
     * The client's centring then moves it by nothing, and the hovers stand where the pack measured them.
     */
    private static int lineWidth(final WheelTimetable.Rows rows, final int body) {
        return textWidth(rows, body) - 2 * PADDING;
    }

    /** Whether {@code item} glints: by its override, the default its item has included, else by its enchantments. */
    static boolean glints(final ItemStack item) {
        final Boolean override = item.getData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);
        return override != null ? override : !item.getEnchantments().isEmpty();
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
