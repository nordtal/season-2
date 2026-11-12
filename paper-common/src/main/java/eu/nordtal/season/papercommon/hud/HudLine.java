package eu.nordtal.season.papercommon.hud;

import eu.nordtal.season.packrendering.ui.Pill;
import java.util.List;
import java.util.Locale;
import org.bukkit.entity.Player;

/** One boss bar line of a {@link Hud}. */
@FunctionalInterface
public interface HudLine {

    /**
     * Returns the pills for {@code player} in {@code locale}, or none to hide the bar.
     * Called on the main thread every frame.
     */
    List<Pill.Content> render(Player player, Locale locale);
}
