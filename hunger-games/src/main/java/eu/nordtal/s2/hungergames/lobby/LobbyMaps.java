package eu.nordtal.s2.hungergames.lobby;

import eu.nordtal.s2.hungergames.config.HungerGamesSpec;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * Slices a language-specific lobby image onto a grid of item-frame-mounted maps -
 * docs/hunger-games.md#the-lobby: "the plugin's job is only to slice a PNG onto a map grid and show
 * each player the image for their language; producing the image is design work, not code."
 * <p>
 * <b>The actual artwork does not exist yet</b> (no {@code lobby/map-en.png} ships in this
 * repository - see this plugin's task brief, "the same 'code exists, art doesn't yet' situation as
 * the HUD glyphs"). {@link #render(World)} tolerates that: a missing file is logged once, clearly,
 * and skipped rather than failing {@code onEnable} - the whole reason this method never throws for
 * a missing resource.
 * </p>
 */
public final class LobbyMaps {

    private static final Logger LOGGER = LoggerFactory.getLogger(LobbyMaps.class);

    private final Plugin plugin;
    private final HungerGamesSpec config;

    public LobbyMaps(final Plugin plugin, final HungerGamesSpec config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Loads {@code lobby/map-<lang>.png} for every language this plugin has a message bundle for,
     * slices it into a {@code columns x rows} grid and mounts one map per item frame found at the
     * configured origin. A missing image for a language is logged once and that language's maps
     * are left as-is (typically empty vanilla maps) rather than aborting the whole plugin.
     *
     * @param world the lobby's world
     */
    public void render(final World world) {
        for (final String language : java.util.List.of("en", "de")) {
            renderLanguage(world, language);
        }
    }

    private void renderLanguage(final World world, final String language) {
        final BufferedImage image = loadImage(language);
        if (image == null) {
            LOGGER.warn("No lobby/map-{}.png found - the lobby map display for {} is unavailable "
                    + "until the image is added. This is expected until the event world's artwork "
                    + "ships; see this plugin's documentation.", language, language);
            return;
        }

        final HungerGamesSpec.LobbySpec lobby = config.lobby();
        final int columns = lobby.mapGridColumns();
        final int rows = lobby.mapGridRows();
        final int cellWidth = image.getWidth() / columns;
        final int cellHeight = image.getHeight() / rows;

        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                final Location frameLocation = new Location(world,
                        lobby.mapFrameOriginX() + column, lobby.mapFrameOriginY() - row, lobby.mapFrameOriginZ());
                final ItemFrame frame = findFrame(world, frameLocation);
                if (frame == null) {
                    LOGGER.warn("No item frame found near {} for the lobby map grid ({}, row {}, "
                            + "column {}) - the hand-built lobby must place one there", frameLocation,
                            language, row, column);
                    continue;
                }

                final BufferedImage cell = image.getSubimage(
                        column * cellWidth, row * cellHeight, cellWidth, cellHeight);
                mountMap(world, frame, cell);
            }
        }
    }

    private BufferedImage loadImage(final String language) {
        final String resource = "lobby/map-" + language + ".png";
        try (InputStream stream = plugin.getClass().getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }
            return ImageIO.read(stream);
        } catch (final IOException exception) {
            LOGGER.warn("Could not read {} - the lobby map display for {} is unavailable", resource,
                    language, exception);
            return null;
        }
    }

    private ItemFrame findFrame(final World world, final Location near) {
        return world.getNearbyEntitiesByType(ItemFrame.class, near, 1, 1, 1).stream().findFirst().orElse(null);
    }

    private void mountMap(final World world, final ItemFrame frame, final BufferedImage cell) {
        final MapView view = Bukkit.createMap(world);
        view.getRenderers().clear();
        view.addRenderer(new StaticImageRenderer(cell));

        final ItemStack mapItem = new ItemStack(org.bukkit.Material.FILLED_MAP);
        final org.bukkit.inventory.meta.MapMeta meta = (org.bukkit.inventory.meta.MapMeta) mapItem.getItemMeta();
        meta.setMapView(view);
        mapItem.setItemMeta(meta);

        frame.setItem(mapItem);
    }

    /** Draws one already-sliced image cell onto its map, once, and does nothing after that. */
    private static final class StaticImageRenderer extends MapRenderer {

        private final Image image;
        private boolean rendered;

        private StaticImageRenderer(final Image image) {
            super(false);
            this.image = image;
        }

        @Override
        public void render(final MapView map, final MapCanvas canvas, final org.bukkit.entity.Player player) {
            if (rendered) {
                return;
            }
            canvas.drawImage(0, 0, image);
            rendered = true;
        }
    }
}
