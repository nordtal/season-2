package eu.nordtal.season.packrendering.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.packrendering.hud.BossBarLine.Pill;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/** The HUD draws every frame, and each rename of a bar is a packet to every viewer. */
class BossBarLineShowTest {

    @Test
    void anUnchangedLineRenamesTheBarOnce() {
        final BossBar bar = BossBarLine.bar();
        final AtomicInteger renames = new AtomicInteger();
        bar.addListener(new BossBar.Listener() {
            @Override
            public void bossBarNameChanged(final BossBar changed, final Component oldName, final Component newName) {
                renames.incrementAndGet();
            }
        });

        BossBarLine.show(bar, List.of(Pill.of("Waiting")));
        BossBarLine.show(bar, List.of(Pill.of("Waiting")));
        BossBarLine.show(bar, List.of(Pill.of("Waiting")));
        assertEquals(1, renames.get(), "a frame that draws the same line sends nothing");

        BossBarLine.show(bar, List.of(Pill.of("Open")));
        assertEquals(2, renames.get(), "a changed line is sent");
    }
}
