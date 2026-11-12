package eu.nordtal.season.packrendering.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.packrendering.ui.Pill;
import eu.nordtal.season.packrendering.ui.Symbol;
import java.util.List;
import net.kyori.adventure.text.format.ShadowColor;
import org.junit.jupiter.api.Test;

class BossBarLineTest {

    @Test
    void theLineIsThePillsWithTheBarsShadowTurnedOff() {
        final List<Pill.Content> pills = List.of(Pill.Content.of(Symbol.RAIL, "Fjordmarkt"));

        assertEquals(
                ShadowColor.none(),
                BossBarLine.render(pills).style().shadowColor(),
                "the boss bar draws its name with a shadow, a dark copy one pixel down and right of every pill");
        assertEquals(Pill.line(pills).children(), BossBarLine.render(pills).children());
    }
}
