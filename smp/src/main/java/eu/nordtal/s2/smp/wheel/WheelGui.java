package eu.nordtal.s2.smp.wheel;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.menu.MenuTitle;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.feedback.Surface;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The wheel itself: a row of prizes travelling past a marker, slowing down, and stopping on the one
 * that was already won.
 *
 * <p>It was a chat line until 2026-09-04 - the spin resolved in SQL and the player was told what
 * they got. Everything that made it worth building is in the five seconds this class adds, and
 * nothing in it decides anything: {@link WheelStrip} explains why the outcome is settled before the
 * first frame is drawn.
 *
 * <h2>The payout can only happen once, and it always happens</h2>
 * The spin is spent before the window opens, so the prize is owed from that moment - which means
 * every way out of this animation has to end in the same payout. There are three: the strip runs to
 * the end, the player closes the window early, or they log off. All three call {@link #finish},
 * which is a one-shot latch; the difference between them is only whether anybody is there to hear
 * the strike.
 *
 * <p>This is the window the old instant payout did not have, and it is the price of the animation
 * rather than an oversight. A player who logs off mid-spin is handled where the payout is - in
 * {@code Wheel} - because that is where "give them the item, or say loudly that nobody could" is a
 * decision rather than a callback.
 */
public final class WheelGui implements Surface {

    private static final int ROWS = 3;
    private static final int STRIP_START = 9;
    private static final int MARKER_ABOVE = 4;
    private static final int MARKER_BELOW = 22;

    private final Inventory inventory;
    private final WheelStrip strip;
    private final List<ItemStack> icons;
    private final SmpSounds sounds;
    private final Consumer<Player> payout;

    private final AtomicBoolean finished = new AtomicBoolean();
    private BukkitTask task;

    public WheelGui(final Messages messages, final Locale locale, final WheelStrip strip,
                    final List<ItemStack> icons, final SmpSounds sounds,
                    final Consumer<Player> payout) {
        this.strip = strip;
        this.icons = List.copyOf(icons);
        this.sounds = sounds;
        this.payout = payout;

        this.inventory = Bukkit.createInventory(this, ROWS * 9, MenuTitle.of(ROWS,
                MessageRenderer.of(messages).get(locale, "smp.wheel.title")));

        final ItemStack marker = marker();
        inventory.setItem(MARKER_ABOVE, marker);
        inventory.setItem(MARKER_BELOW, marker);
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
        }
        payout.accept(player);
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
            inventory.setItem(STRIP_START + cell, icons.get(cells[cell]));
        }
    }

    /**
     * The two panes that point at the winning cell.
     *
     * <p>Named with an empty component rather than left alone: an unnamed pane shows its vanilla
     * name, and "Red Stained Glass Pane" floating over a prize wheel is exactly the kind of
     * unfinished edge this pass exists to remove.
     */
    private static ItemStack marker() {
        final ItemStack pane = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        pane.editMeta(meta -> meta.displayName(
                Component.empty().decoration(TextDecoration.ITALIC, false)));
        return pane;
    }
}
