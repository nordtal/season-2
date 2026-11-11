package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.DialogPanel;
import eu.nordtal.season.packrendering.ui.DialogTitle;
import eu.nordtal.season.packrendering.ui.Letters;
import eu.nordtal.season.packrendering.ui.Look;
import eu.nordtal.season.packrendering.ui.Pen;
import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.packrendering.ui.Tooltip;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.UseCooldown;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The wooden wheel as a dialog of 14 item bodies: the disc, 12 prizes and the overlay.
 * The pack draws each over the wheel and turns it by the cooldown of {@link #GROUP}. Prize body 4 carries the plate.
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
                    Slot.label("again-button"),
                    Slot.label("again-title"),
                    Slot.label("again-cost"),
                    Slot.label("again-none"))
            .titled("Wheel of Fortune");

    /** The item group whose cooldown plays the spin. */
    static final Key GROUP = Key.key("nordtal", "wheel");

    /** The panel's top, which the header draws from. */
    private static final int TOP = 64;

    /** The prize body that carries the plate's row. */
    private static final int PLATE_BODY = 4;

    private static final int PLATE_ITEM = 100;
    private static final int DESCRIPTION = 86;
    private static final int PLATE_WIDTH = 75;

    /** The plate's left from the description's text, so it stands at the panel's left + 114. */
    private static final int PLATE_X = 1;

    /** The spins line's ui text font under its text row, the description's second. */
    private static final int SPINS_SHIFT = 16;

    private WheelDialog() {}

    /** The words on the wheel, read from {@link #DOCUMENT} in the player's language. */
    record Texts(Component heading, String name, String spins, String again, String title, String cost, String none) {}

    /** The dialog of {@code plan}, one of {@code prizes} per body; {@code click}, null without spins, spins again. */
    static Dialog of(
            final WheelPlan plan,
            final List<ItemStack> prizes,
            final Texts texts,
            final String language,
            final boolean spinLeft,
            final @Nullable ClickEvent click) {
        final List<DialogBody> bodies = new ArrayList<>(WheelPlan.PLACES + 2);
        bodies.add(body(stack(named(texts.heading()), "disc", plan.disc())));
        for (int body = 0; body < WheelPlan.PLACES; body++) {
            final ItemStack stack = stack(prizes.get(body), "p" + body, plan.prize(body));
            bodies.add(
                    body == PLATE_BODY
                            ? DialogBody.item(stack)
                                    .showDecorations(false)
                                    .showTooltip(false)
                                    .width(PLATE_ITEM)
                                    .height(1)
                                    .description(DialogBody.plainMessage(plateRow(texts, spinLeft, click), DESCRIPTION))
                                    .build()
                            : body(stack));
        }
        bodies.add(body(stack(named(texts.heading()), "overlay", plan.overlay(language))));
        final Component title = DialogTitle.of(DialogPanel.WHEEL_137, TOP)
                .header(Symbol.WHEEL, texts.name(), List.of())
                .build();
        return Dialog.create(builder ->
                builder.empty().base(Dialogs.base(title).body(bodies).build()).type(Dialogs.bodyOnly()));
    }

    /** The plate, whose hover says what a spin costs or that none is left, and under it the spins line. */
    static Component plateRow(final Texts texts, final boolean spinLeft, final @Nullable ClickEvent click) {
        final Component hover =
                spinLeft ? Tooltip.hover(texts.title(), texts.cost(), null) : Tooltip.hover(texts.none(), null, null);
        final Component plate = BodyLine.start()
                .at(PLATE_X)
                .button(
                        new BodyLine.Press(click, hover),
                        line -> line.plate(PLATE_WIDTH, null, texts.again(), spinLeft ? Look.PRIMARY : Look.DISABLED))
                .build();
        final String spins = Letters.cut(texts.spins(), PLATE_WIDTH);
        final Component line = Pen.start()
                .moveTo(PLATE_X + (PLATE_WIDTH - Letters.visibleWidth(spins) + 1) / 2)
                .text(SPINS_SHIFT, spins, Colours.SOFT)
                .build();
        return Component.textOfChildren(plate, Component.newline(), line);
    }

    private static DialogBody body(final ItemStack stack) {
        return DialogBody.item(stack)
                .showDecorations(false)
                .showTooltip(false)
                .width(1)
                .height(1)
                .build();
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
