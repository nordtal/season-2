package eu.nordtal.s2.stewardagent.gamedata;

import eu.nordtal.s2.database.game.GameDataStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Every item's icon in one PNG, {@value #COLUMNS} to a row, with the slot of each. */
final class IconSheet {

    /** Icons to a row: 1024 pixels wide. */
    static final int COLUMNS = 32;

    private IconSheet() {}

    /** Draws {@code items} from {@code assets}; an item the jar draws nothing for has no slot. */
    static GameDataStore.Icons draw(final AssetSource assets, final List<String> items) {
        final IconPainter painter = new IconPainter(assets);
        final List<BufferedImage> icons = new ArrayList<>();
        final Map<String, Integer> slots = new LinkedHashMap<>();
        for (final String item : items) {
            final BufferedImage icon = painter.paint(item);
            if (icon != null) {
                slots.put(item, icons.size());
                icons.add(icon);
            }
        }
        final int rows = Math.max(1, (icons.size() + COLUMNS - 1) / COLUMNS);
        final BufferedImage sheet =
                new BufferedImage(COLUMNS * Raster.ICON, rows * Raster.ICON, BufferedImage.TYPE_INT_ARGB);
        for (int slot = 0; slot < icons.size(); slot++) {
            sheet.getGraphics()
                    .drawImage(icons.get(slot), (slot % COLUMNS) * Raster.ICON, (slot / COLUMNS) * Raster.ICON, null);
        }
        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            ImageIO.write(sheet, "png", png);
        } catch (final IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return new GameDataStore.Icons(png.toByteArray(), COLUMNS, slots);
    }
}
