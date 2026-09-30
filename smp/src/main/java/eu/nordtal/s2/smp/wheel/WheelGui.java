package eu.nordtal.s2.smp.wheel;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.smp.SmpMessages;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.feedback.Surface;
import eu.nordtal.s2.smp.menu.BlankItem;
import eu.nordtal.s2.smp.menu.SlotGeometry;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * The wheel itself: twelve prizes travelling round a ring, slowing down, and stopping on the one already won.
 *
 * Every way out calls {@link #finish}, a one-shot latch that pays; "again" is live only once the wheel stops.
 */
public final class WheelGui implements Surface {

    private final Inventory inventory;
    private final WheelStrip strip;
    private final List<ItemStack> icons;
    private final SmpSounds sounds;
    private final Consumer<Player> payout;
    private final Messages messages;
    private final Locale locale;

    /** What to run when the player asks for another spin, or null while there is none to give. */
    private final @Nullable Runnable again;

    private final AtomicBoolean finished = new AtomicBoolean();
    private @Nullable BukkitTask task;

    /**
     * Builds the window for one spin.
     *
     * @param spinsLeft how many spins the player has after this one, which is what the hub shows
     * @param earnAt the lowest contribution share that earns an extra spin, in percent
     * @param again runs another spin, or null when this player has none left
     */
    public WheelGui(
            final Messages messages,
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
        this.messages = messages;
        this.locale = locale;
        this.again = again;

        final MessageRenderer renderer = MessageRenderer.of(messages);
        this.inventory = Bukkit.createInventory(
                this,
                WheelPanel.ROWS * SlotGeometry.COLUMNS,
                WheelPanel.title(
                        renderer.format(locale, MESSAGES.smp().wheel().title()),
                        String.valueOf(spinsLeft),
                        messages.format(locale, MESSAGES.smp().wheel().spinsLeft(spinsLeft)),
                        messages.format(locale, MESSAGES.smp().wheel().ruleTop()),
                        messages.format(locale, MESSAGES.smp().wheel().ruleBottom(earnAt)),
                        messages.format(locale, MESSAGES.smp().wheel().againButton())));

        final ItemStack hub = BlankItem.of(
                renderer.format(locale, MESSAGES.smp().wheel().hub(spinsLeft)),
                List.of(renderer.format(locale, MESSAGES.smp().wheel().hubHint(earnAt))));
        inventory.setItem(WheelPanel.HUB_SLOT, hub);
        WheelPanel.INFO_SLOTS.forEach(slot -> inventory.setItem(slot, hub));

        setAgain(MESSAGES.smp().wheel().againWaiting(), MESSAGES.smp().wheel().againWaitingHint());
        draw(0);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Opens the window and runs the animation, on the main thread. */
    public void start(final Plugin plugin, final Player player) {
        player.openInventory(inventory);
        step(plugin, player, 0);
    }

    /** A click inside this window: runs another spin if it hit the "again" button and the wheel has stopped. */
    public void click(final Player player, final int rawSlot) {
        if (!WheelPanel.AGAIN_SLOTS.contains(rawSlot)) {
            return;
        }
        if (!finished.get() || again == null) {
            // Still spinning, or nothing left to spin with; both refuse with a sound instead of silence.
            sounds.play(player, Feedback.REFUSED);
            return;
        }
        // The new spin opens its own window, closing this one.
        again.run();
    }

    /**
     * Ends the spin exactly once: stops the animation, and pays.
     *
     * @param celebrate whether the player is still watching, so the strike plays
     */
    public void finish(final Player player, final boolean celebrate) {
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
        final MessageRenderer renderer = MessageRenderer.of(messages);
        final ItemStack item = BlankItem.of(renderer.format(locale, name), List.of(renderer.format(locale, hint)));
        WheelPanel.AGAIN_SLOTS.forEach(slot -> inventory.setItem(slot, item));
    }

    private void step(final Plugin plugin, final Player player, final int step) {
        if (finished.get() || !player.isOnline()) {
            return;
        }
        draw(step);
        sounds.play(player, Feedback.COUNTDOWN_TICK);

        final int delay = WheelStrip.delay(step);
        final int next = step + 1;
        task = Bukkit.getScheduler()
                .runTaskLater(
                        plugin,
                        () -> {
                            if (next < WheelStrip.steps()) {
                                step(plugin, player, next);
                            } else {
                                // The last delay is the beat after the wheel stops, not a gap before a frame.
                                finish(player, true);
                            }
                        },
                        delay);
    }

    private void draw(final int step) {
        final int[] cells = strip.cells(step);
        for (int cell = 0; cell < cells.length; cell++) {
            inventory.setItem(WheelPanel.CELL_SLOTS.get(cell), icons.get(cells[cell]));
        }
    }
}
