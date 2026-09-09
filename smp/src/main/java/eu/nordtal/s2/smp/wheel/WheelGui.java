package eu.nordtal.s2.smp.wheel;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.menu.SlotGeometry;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.papercommon.menu.BlankItem;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.feedback.Surface;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The wheel itself: twelve prizes travelling round a ring, slowing down, and stopping on the one
 * that was already won.
 *
 * <p>It was a chat line until 2026-09-04 - the spin resolved in SQL and the player was told what
 * they got. Everything that made it worth building is in the five seconds this class adds, and
 * nothing in it decides anything: {@link WheelStrip} explains why the outcome is settled before the
 * first frame is drawn. The surface is {@link WheelPanel}, design {@code W3} with the ring moved two
 * slot columns left so the controls have somewhere to be.
 *
 * <h2>The payout can only happen once, and it always happens</h2>
 * The spin is spent before the window opens, so the prize is owed from that moment - which means
 * every way out of this animation has to end in the same payout. There are three: the strip runs to
 * the end, the player closes the window early, or they log off. All three call {@link #finish},
 * which is a one-shot latch; the difference between them is only whether anybody is there to hear
 * the strike.
 *
 * <h2>"Again" is a button that does not exist yet when the window opens</h2>
 * A chest's title is fixed once it is open, so the plate under the button is painted from the first
 * frame. What is <em>not</em> there until the wheel stops is the item in those three slots: while
 * the animation runs they carry a tooltip saying to wait, and {@link #finish} swaps it for the one
 * that spins again. That is the whole guard against the failure the design artifact names - a
 * double click buying two spins at once - and it is a swap rather than a flag because a player who
 * hovers a dead button wants to be told why.
 */
public final class WheelGui implements Surface {

    private final Inventory inventory;
    private final WheelStrip strip;
    private final List<ItemStack> icons;
    private final SmpSounds sounds;
    private final Consumer<Player> payout;
    private final Messages messages;
    private final Locale locale;

    /** What to run when the player asks for another spin - null while there is none to give. */
    private final Runnable again;

    private final AtomicBoolean finished = new AtomicBoolean();
    private BukkitTask task;

    /**
     * @param spinsLeft how many spins the player has after this one, which is what the hub shows
     * @param earnAt    the lowest contribution share that earns an extra spin, in percent
     * @param again     runs another spin, or null when this player has none left
     */
    public WheelGui(final Messages messages, final Locale locale, final WheelStrip strip,
                    final List<ItemStack> icons, final SmpSounds sounds,
                    final int spinsLeft, final int earnAt, final Runnable again,
                    final Consumer<Player> payout) {
        this.strip = strip;
        this.icons = List.copyOf(icons);
        this.sounds = sounds;
        this.payout = payout;
        this.messages = messages;
        this.locale = locale;
        this.again = again;

        final MessageRenderer renderer = MessageRenderer.of(messages);
        this.inventory = Bukkit.createInventory(this,
                WheelPanel.ROWS * SlotGeometry.COLUMNS,
                WheelPanel.title(renderer.get(locale, "smp.wheel.title"),
                        String.valueOf(spinsLeft),
                        messages.format(locale, "smp.wheel.spins-left",
                                Map.of("spins", spinsLeft)),
                        messages.get(locale, "smp.wheel.rule-top"),
                        messages.format(locale, "smp.wheel.rule-bottom",
                                Map.of("percent", earnAt)),
                        messages.get(locale, "smp.wheel.again-button")));

        final ItemStack hub = BlankItem.of(
                renderer.format(locale, "smp.wheel.hub", "spins", spinsLeft),
                List.of(renderer.format(locale, "smp.wheel.hub-hint", "percent", earnAt)));
        inventory.setItem(WheelPanel.HUB_SLOT, hub);
        WheelPanel.INFO_SLOTS.forEach(slot -> inventory.setItem(slot, hub));

        setAgain("smp.wheel.again-waiting", "smp.wheel.again-waiting-hint");
        draw(0);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Opens the window and runs the animation. Main thread. */
    public void start(final Plugin plugin, final Player player) {
        player.openInventory(inventory);
        step(plugin, player, 0);
    }

    /**
     * A click inside this window.
     *
     * <p>Nothing here is ever picked up, so the caller cancels the event whatever this answers; what
     * this decides is only whether the click was the "again" button and whether it may run yet.</p>
     */
    public void click(final Player player, final int rawSlot) {
        if (!WheelPanel.AGAIN_SLOTS.contains(rawSlot)) {
            return;
        }
        if (!finished.get() || again == null) {
            // Still spinning, or nothing left to spin with. Both are refusals and both say so with
            // a sound: silence on a button that is visibly there reads as a broken menu.
            sounds.play(player, Feedback.REFUSED);
            return;
        }
        // The new spin opens its own window, which closes this one - and this one has already
        // latched, so the close pays nothing a second time.
        again.run();
    }

    /**
     * Ends the spin exactly once: stops the animation, and pays.
     *
     * @param celebrate whether the player is still watching - the strike is for the moment the
     *                  wheel stops, and playing it into an empty screen after somebody has already
     *                  walked away is worse than silence
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
            setAgain(again == null ? "smp.wheel.again-none" : "smp.wheel.again",
                    again == null ? "smp.wheel.again-none-hint" : "smp.wheel.again-hint");
        }
        payout.accept(player);
    }

    private void setAgain(final String name, final String hint) {
        final MessageRenderer renderer = MessageRenderer.of(messages);
        final ItemStack item = BlankItem.of(renderer.get(locale, name),
                List.of(renderer.get(locale, hint)));
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
        task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (next < WheelStrip.steps()) {
                step(plugin, player, next);
            } else {
                // The last delay is the beat after the wheel stops, not a gap before a frame.
                finish(player, true);
            }
        }, delay);
    }

    private void draw(final int step) {
        final int[] cells = strip.cells(step);
        for (int cell = 0; cell < cells.length; cell++) {
            inventory.setItem(WheelPanel.CELL_SLOTS.get(cell), icons.get(cells[cell]));
        }
    }
}
