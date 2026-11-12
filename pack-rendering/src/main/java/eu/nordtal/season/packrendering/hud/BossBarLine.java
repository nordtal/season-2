package eu.nordtal.season.packrendering.hud;

import eu.nordtal.season.packrendering.ui.Pill;
import java.util.List;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;

/** A HUD line: the kit's pills, 3 apart, as one boss bar's name without shadow. */
public final class BossBarLine {

    private BossBarLine() {}

    public static BossBar bar() {
        return BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
    }

    /** Names the bar only when its line changed: every call to name is sent to each viewer, even an equal one. */
    public static void show(final BossBar bar, final List<Pill.Content> pills) {
        final Component name = render(pills);
        if (!name.equals(bar.name())) {
            bar.name(name);
        }
    }

    /** The boss bar draws its name with a shadow, so the line turns it off on its root. */
    public static Component render(final List<Pill.Content> pills) {
        return Pill.line(pills).shadowColor(ShadowColor.none());
    }
}
