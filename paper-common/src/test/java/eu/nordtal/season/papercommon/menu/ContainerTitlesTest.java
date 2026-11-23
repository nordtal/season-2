package eu.nordtal.season.papercommon.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

/** The name a placed container's drawn title shows: renamed, a large chest's halves, and cut to the header's room. */
class ContainerTitlesTest {

    private static final Component CHEST = Component.translatable("container.chest");
    private static final Component DOUBLE = Component.translatable("container.chestDouble");

    @Test
    void aRenamedContainerKeepsItsNameAndAPlainOneVanillasTranslatedName() {
        final Component renamed = Component.text("Keller");
        assertEquals(renamed, ContainerTitles.name(renamed, List.of()));
        assertEquals(CHEST, ContainerTitles.name(CHEST, List.of()));
    }

    @Test
    void aLargeChestTakesEitherHalfsName() {
        final Component left = Component.text("Keller");
        final Component right = Component.text("Lager");
        assertEquals(left, ContainerTitles.name(DOUBLE, Arrays.asList(left, null)));
        assertEquals(right, ContainerTitles.name(DOUBLE, Arrays.asList(null, right)));
        assertEquals(left, ContainerTitles.name(DOUBLE, List.of(left, right)), "the left half first");
        assertEquals(DOUBLE, ContainerTitles.name(DOUBLE, Arrays.asList(null, null)));
        assertEquals(right, ContainerTitles.name(right, List.of(left, right)), "vanilla's pick stands");
    }

    @Test
    void aLongNameIsCutWithAnEllipsisAndATranslatedOneIsLeftToTheClient() {
        final String longName = "Vorratskammer der Nordhafen-Gilde";
        final Component title = ContainerTitles.title(3, Component.text(longName), List.of());
        final String shown = ((TextComponent) title.children().getLast()).content();
        assertEquals("…", shown.substring(shown.length() - 1));
        assertEquals(longName.substring(0, shown.length() - 1), shown.substring(0, shown.length() - 1));
        assertEquals(
                "Eichenholzboot mit Truhe",
                ((TextComponent) ContainerTitles.title(3, Component.text("Eichenholzboot mit Truhe"), List.of())
                                .children()
                                .getLast())
                        .content());
        assertEquals(
                DOUBLE, ContainerTitles.title(6, DOUBLE, List.of()).children().getLast());
        // Boundary: 160 px (32 * 'l' at 3 px + 16 * 'a' at 6 px? Use 'a' (6 px) * 26 + 'i' (2 px) * 2 = 160 px).
        final String exact160 = "a".repeat(26) + "ii";
        assertEquals(
                exact160,
                ((TextComponent) ContainerTitles.title(3, Component.text(exact160), List.of())
                                .children()
                                .getLast())
                        .content());
        final String over161 = exact160 + ".";
        final String cut = ((TextComponent) ContainerTitles.title(3, Component.text(over161), List.of())
                        .children()
                        .getLast())
                .content();
        assertEquals("…", cut.substring(cut.length() - 1));
    }
}
