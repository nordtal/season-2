package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.papercommon.menu.BlankItem;
import eu.nordtal.season.papercommon.menu.Menu;
import eu.nordtal.season.papercommon.menu.MenuClick;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.feedback.SmpSounds;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/** Every end of the spin calls {@code finish}, which pays out once. The "again" button works after the wheel stops. */
public final class WheelGui extends Menu {

    /** @param spins the spins left after this one */
    record Window(@Example("3") long spins) {}

    record Again(State state) {}

    /** The again button waits for the spin, then takes another or has none left. */
    enum State {
        WAITING,
        READY,
        NONE
    }

    static final DocumentSpec<Window> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "wheel",
                    "Menus",
                    Window.class,
                    Slot.item("hub"),
                    Slot.item("again", Again.class),
                    Slot.label("spins-left"),
                    Slot.label("rule-top"),
                    Slot.label("rule-bottom"),
                    Slot.label("again-button"))
            .titled("Wheel of Fortune");

    private final Inventory inventory;
    private final WheelStrip strip;
    private final List<ItemStack> icons;
    private final SmpSounds sounds;
    private final Consumer<Player> payout;
    private final GameDocument document;

    /** It is null when the player has no spin left. */
    private final @Nullable Runnable again;

    private final AtomicBoolean finished = new AtomicBoolean();
    private Scheduler.@Nullable Task task;

    public WheelGui(
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final Locale locale,
            final WheelStrip strip,
            final List<ItemStack> icons,
            final SmpSounds sounds,
            final int spinsLeft,
            final @Nullable Runnable again,
            final Consumer<Player> payout) {
        this.strip = strip;
        this.icons = List.copyOf(icons);
        this.sounds = sounds;
        this.payout = payout;
        this.again = again;
        this.document =
                new GameDocument(renderer, documents.applied(DOCUMENT), Viewer.of(locale), new Window(spinsLeft));

        this.inventory = frame(
                WheelPanel.ROWS,
                WheelPanel.title(
                        document.title(),
                        String.valueOf(spinsLeft),
                        document.label("spins-left"),
                        document.label("rule-top"),
                        document.label("rule-bottom"),
                        document.label("again-button")));

        final ItemStack hub = BlankItem.of(document.item("hub"));
        inventory.setItem(WheelPanel.HUB_SLOT, hub);
        WheelPanel.INFO_SLOTS.forEach(slot -> inventory.setItem(slot, hub));

        setAgain(State.WAITING);
        draw(0);
    }

    /** Main thread only. */
    public void start(final Plugin plugin, final Player player) {
        open(player);
        step(plugin, player, 0);
    }

    @Override
    protected MenuClick click(final Player player, final int slot) {
        if (!WheelPanel.AGAIN_SLOTS.contains(slot)) {
            return MenuClick.nothing();
        }
        if (!finished.get() || again == null) {
            return MenuClick.refused();
        }
        again.run();
        return MenuClick.nothing();
    }

    @Override
    protected void closed(final Player player) {
        finish(player, false);
    }

    @Override
    protected void stopped(final Player player) {
        finish(player, false);
    }

    /** Only the first call acts. */
    private void finish(final Player player, final boolean celebrate) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (celebrate && player.isOnline()) {
            sounds.play(player, Feedback.BIG_SUCCESS);
            setAgain(again == null ? State.NONE : State.READY);
        }
        payout.accept(player);
    }

    private void setAgain(final State state) {
        final ItemStack item = BlankItem.of(document.item("again", new Again(state)));
        WheelPanel.AGAIN_SLOTS.forEach(slot -> inventory.setItem(slot, item));
    }

    private void step(final Plugin plugin, final Player player, final int step) {
        if (finished.get() || !player.isOnline()) {
            return;
        }
        draw(step);
        sounds.play(player, Feedback.COUNTDOWN_TICK);

        final int next = step + 1;
        task = PaperScheduler.of(plugin).onMainAfter(WheelStrip.delay(step), () -> {
            if (next < WheelStrip.steps()) {
                step(plugin, player, next);
            } else {
                finish(player, true);
            }
        });
    }

    private void draw(final int step) {
        final int[] cells = strip.cells(step);
        for (int cell = 0; cell < cells.length; cell++) {
            inventory.setItem(WheelPanel.CELL_SLOTS.get(cell), icons.get(cells[cell]));
        }
    }
}
