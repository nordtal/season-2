package eu.nordtal.season.stewardagent.gamedata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** What names the painter: every class that draws, and nothing that only fetches. */
class PainterTest {

    @Test
    void theIdentityIsStableAndFitsTheColumn() {
        assertEquals(Painter.id(), Painter.identify(Painter.drawingClasses()));
        assertTrue(Painter.id().matches("[0-9a-f]{16}"), Painter.id());
    }

    @Test
    void everyClassThatDrawsCountsAndNoneThatOnlyFetches() {
        final Map<String, byte[]> classes = Painter.drawingClasses();

        for (final String drawing :
                new String[] {"IconPainter", "IconSheet", "Raster", "Model", "StandIns", "AssetSource", "BannerLayer"
                }) {
            assertTrue(classes.containsKey(drawing + ".class"), drawing + " is hashed");
        }
        assertTrue(classes.keySet().stream().anyMatch(name -> name.startsWith("IconPainter$")), "nested classes too");
        for (final String fetching : new String[] {"GameAssets", "MojangClient", "ClientJars", "Painter"}) {
            assertTrue(
                    classes.keySet().stream()
                            .noneMatch(name -> name.startsWith(fetching + ".") || name.startsWith(fetching + "$")),
                    fetching + " does not change a drawing");
        }
    }

    @Test
    void aChangeToOneClassFileIsAnotherPainter() {
        final Map<String, byte[]> before = Map.of("IconPainter.class", new byte[] {1, 2, 3});
        final Map<String, byte[]> after = Map.of("IconPainter.class", new byte[] {1, 2, 4});

        assertNotEquals(Painter.identify(before), Painter.identify(after));
        assertEquals(Painter.identify(before), Painter.identify(Map.of("IconPainter.class", new byte[] {1, 2, 3})));
    }
}
