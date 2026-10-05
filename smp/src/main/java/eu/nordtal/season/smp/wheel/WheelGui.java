package eu.nordtal.season.smp.wheel;

import static eu.nordtal.season.smp.SmpMessages.MESSAGES;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.menu.BlankItem;
import eu.nordtal.season.papercommon.menu.Menu;
import eu.nordtal.season.papercommon.menu.MenuClick;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.SmpMessages;
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

/**
 * The wheel itself: twelve prizes travelling round a ring, slowing down, and stopping on the one already won.
 *
 * Every way out calls {@code finish}, a one-shot latch that pays; "again" is live only once the wheel stops.
 */
public final class WheelGui extends Menu {

    private final Inventory inventory;
    private final WheelStrip strip;
    private final List<ItemStack> icons;
    private final SmpSounds sounds;
    private final Consumer<Player> payout;
    private final MessageRenderer renderer;
    private final Locale locale;

    /** What to run when the player asks for another spin, or null while there is none to give. */
    private final @Nullable Runnable again;

    private final AtomicBoolean finished = new AtomicBoolean();
    private Scheduler.@Nullable Task task;

    /**
     * Builds the window for one spin.
     *
     * @param spinsLeft how many spins the player has after this one, which is what the hub shows
     * @param earnAt the lowest contribution share that earns an extra spin, in percent
     * @param again runs another spin, or null when this player has none left
     */
    public WheelGui(
            final MessageRenderer renderer,
            final Locale locale,
            final WheelStrip strip,
            final List<ItemStack> icons,
            final SmpSounds sounds,
            final int spinsLeft,
            final int earnAt,
            final @Nullable Runnable again,
            final Consumer<Player> payout) {
        this.strip = strip;
        this.icons = List.copyOf(icons);
        this.sounds = sounds;
        this.payout = payout;
        this.renderer = renderer;
        this.locale = locale;
        this.again = again;

        this.inventory = frame(
                WheelPanel.ROWS,
                WheelPanel.title(
                        renderer.format(locale, MESSAGES.smp().wheel().title()),
                        String.valueOf(spinsLeft),
                        renderer.raw().format(locale, MESSAGES.smp().wheel().spinsLeft(spinsLeft)),
                        renderer.raw().format(locale, MESSAGES.smp().wheel().ruleTop()),
                        renderer.raw().format(locale, MESSAGES.smp().wheel().ruleBottom(earnAt)),
                        renderer.raw().format(locale, MESSAGES.smp().wheel().againButton())));

        final ItemStack hub = BlankItem.of(
                renderer.format(locale, MESSAGES.smp().wheel().hub(spinsLeft)),
                List.of(renderer.format(locale, MESSAGES.smp().wheel().hubHint(earnAt))));
        inventory.setItem(WheelPanel.HUB_SLOT, hub);
        WheelPanel.INFO_SLOTS.forEach(slot -> inventory.setItem(slot, hub));

        setAgain(MESSAGES.smp().wheel().againWaiting(), MESSAGES.smp().wheel().againWaitingHint());
        draw(0);
    }

    /** Opens the window and runs the animation, on the main thread. */
    public void start(final Plugin plugin, final Player player) {
        open(player);
        step(plugin, player, 0);
    }

    /** Runs another spin if the click hit the "again" button and the wheel has stopped. */
    @Override
    protected MenuClick click(final Player player, final int slot) {
        if (!WheelPanel.AGAIN_SLOTS.contains(slot)) {
            return MenuClick.nothing();
        }
        if (!finished.get() || again == null) {
            // Still spinning, or nothing left to spin with.
            return MenuClick.refused();
        }
        // The new spin opens its own window, closing this one.
        again.run();
        return MenuClick.nothing();
    }

    /** A window closed before the wheel stopped still pays out, without the strike. */
    @Override
    protected void closed(final Player player) {
        finish(player, false);
    }

    /** A wheel still spinning at shutdown pays out now, since the spin was spent before the first frame. */
    @Override
    protected void stopped(final Player player) {
        finish(player, false);
    }

    /**
     * Ends the spin exactly once: stops the animation, and pays.
     *
     * @param celebrate whether the player is still watching, so the strike plays
     */
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
            final SmpMessages.Smp.Wheel wheel = MESSAGES.smp().wheel();
            setAgain(
                    again == null ? wheel.againNone() : wheel.again(),
                    again == null ? wheel.againNoneHint() : wheel.againHint());
        }
        payout.accept(player);
    }

    private void setAgain(final MessageRef name, final MessageRef hint) {
        final ItemStack item = BlankItem.of(renderer.format(locale, name), List.of(renderer.format(locale, hint)));
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
                // The last delay is the beat after the wheel stops, not a gap before a frame.
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
