package eu.nordtal.season.stewardagent.gamedata;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Draws one item's icon as the inventory shows it, from the item definition and the models in the client jar.
 *
 * Layers or boxes in the GUI pose, first frames, default tints; what the game draws in code gets a stand-in.
 */
final class IconPainter {

    private static final int WHITE = 0xFFFFFFFF;

    /** A dye's colour on a banner, as the game tints the cloth with it. */
    private static final Map<String, Integer> DYES = Map.ofEntries(
            Map.entry("white", 0xF9FFFE),
            Map.entry("orange", 0xF9801D),
            Map.entry("magenta", 0xC74EBD),
            Map.entry("light_blue", 0x3AB3DA),
            Map.entry("yellow", 0xFED83D),
            Map.entry("lime", 0x80C71F),
            Map.entry("pink", 0xF38BAA),
            Map.entry("gray", 0x474F52),
            Map.entry("light_gray", 0x9D9D97),
            Map.entry("cyan", 0x169C9C),
            Map.entry("purple", 0x8932B8),
            Map.entry("blue", 0x3C44AA),
            Map.entry("brown", 0x835432),
            Map.entry("green", 0x5E7C16),
            Map.entry("red", 0xB02E26),
            Map.entry("black", 0x1D1D21));

    /** A head's face, from its mob's texture, by the kind the definition names. */
    private static final Map<String, Crop> HEADS = Map.of(
            "skeleton", new Crop("minecraft:entity/skeleton/skeleton", 64, 8, 8, 8, 8),
            "wither_skeleton", new Crop("minecraft:entity/skeleton/wither_skeleton", 64, 8, 8, 8, 8),
            "zombie", new Crop("minecraft:entity/zombie/zombie", 64, 8, 8, 8, 8),
            "creeper", new Crop("minecraft:entity/creeper/creeper", 64, 8, 8, 8, 8),
            "piglin", new Crop("minecraft:entity/piglin/piglin", 64, 8, 8, 10, 8),
            "player", new Crop("minecraft:entity/player/wide/steve", 64, 8, 8, 8, 8),
            "dragon", new Crop("minecraft:entity/enderdragon/dragon", 256, 128, 46, 16, 16));

    /** Other renderers whose look is one face of their entity texture. */
    private static final Map<String, Crop> FACES = Map.of(
            "shield", new Crop("minecraft:entity/shield/shield_base_nopattern", 64, 1, 1, 12, 22),
            "decorated_pot", new Crop("minecraft:entity/decorated_pot/decorated_pot_side", 16, 0, 0, 16, 16));

    private final AssetSource assets;

    IconPainter(final AssetSource assets) {
        this.assets = assets;
    }

    /** The icon of {@code item}, such as {@code minecraft:oak_log}, or none when the jar has no definition for it. */
    @Nullable
    BufferedImage paint(final String item) {
        return paint(item, List.of());
    }

    /** The icon of a banner {@code item} carrying {@code patterns}; any other item ignores them. */
    @Nullable
    BufferedImage paint(final String item, final List<BannerLayer> patterns) {
        final Stack stack = new Stack(item, patterns);
        final JsonObject definition = assets.json(AssetSource.path(AssetSource.id(item), "items", ".json"));
        if (definition == null || !(definition.get("model") instanceof JsonObject model)) {
            return null;
        }
        final Raster raster = new Raster();
        return draw(raster, model, Transformation.NONE, stack) ? raster.image() : null;
    }

    /** Draws the branch of a definition the inventory shows when nothing about the stack is special. */
    private boolean draw(final Raster raster, final JsonObject node, final Transformation outer, final Stack stack) {
        final String type = kind(node);
        final Transformation transformation =
                Transformation.of(node.get("transformation")).then(outer);
        return switch (type) {
            case "model" -> model(raster, text(node, "model"), tints(node), transformation);
            case "composite" -> {
                boolean drawn = false;
                if (node.get("models") instanceof JsonArray parts) {
                    for (final JsonElement part : parts) {
                        if (part instanceof JsonObject branch) {
                            drawn |= draw(raster, branch, transformation, stack);
                        }
                    }
                }
                yield drawn;
            }
            case "select", "range_dispatch" -> {
                final JsonObject chosen = node.get("fallback") instanceof JsonObject fallback
                        ? fallback
                        : first(node.get(type.equals("select") ? "cases" : "entries"));
                yield chosen != null && draw(raster, chosen, transformation, stack);
            }
            case "condition" ->
                node.get("on_false") instanceof JsonObject otherwise && draw(raster, otherwise, transformation, stack);
            case "special" -> special(raster, node, stack, transformation);
            default -> false;
        };
    }

    /** The first case's or entry's model of a list, which a select without a fallback shows. */
    private static @Nullable JsonObject first(final @Nullable JsonElement list) {
        if (list instanceof JsonArray array
                && !array.isEmpty()
                && array.get(0) instanceof JsonObject first
                && first.get("model") instanceof JsonObject model) {
            return model;
        }
        return null;
    }

    private boolean model(
            final Raster raster,
            final @Nullable String id,
            final List<Integer> tints,
            final Transformation transformation) {
        if (id == null) {
            return false;
        }
        final Model model = Model.resolve(assets, id);
        if (model.elements() != null && !model.generated()) {
            return boxes(raster, model, tints, transformation);
        }
        return layers(raster, model, tints);
    }

    /** A generated item: {@code layer0} and the layers above it, each with its own tint. */
    private boolean layers(final Raster raster, final Model model, final List<Integer> tints) {
        boolean drawn = false;
        for (int layer = 0; ; layer++) {
            final String id = model.texture("#layer" + layer);
            if (id == null) {
                return drawn;
            }
            final BufferedImage texture = assets.texture(id);
            if (texture != null) {
                raster.flat(texture, layer < tints.size() ? tints.get(layer) : WHITE);
                drawn = true;
            }
        }
    }

    private boolean boxes(
            final Raster raster, final Model model, final List<Integer> tints, final Transformation transformation) {
        final Model.Pose pose = model.gui() == null ? Model.Pose.IDENTITY : model.gui();
        boolean drawn = false;
        final JsonArray elements = model.elements();
        if (elements == null) {
            return false;
        }
        for (final JsonElement element : elements) {
            if (element instanceof JsonObject box && box.get("faces") instanceof JsonObject faces) {
                drawn |= box(raster, model, box, faces, tints, transformation, pose);
            }
        }
        return drawn;
    }

    private boolean box(
            final Raster raster,
            final Model model,
            final JsonObject box,
            final JsonObject faces,
            final List<Integer> tints,
            final Transformation transformation,
            final Model.Pose pose) {
        final Vec3 from = Vec3.of(box.get("from"), 0);
        final Vec3 to = Vec3.of(box.get("to"), 16);
        final boolean shaded = !(box.get("shade") instanceof JsonPrimitive shade) || shade.getAsBoolean();
        final ElementRotation rotation = ElementRotation.of(box.get("rotation"));
        boolean drawn = false;
        for (final Face face : Face.values()) {
            if (!(faces.get(face.key()) instanceof JsonObject declared)) {
                continue;
            }
            final String textureId = model.texture(Objects.requireNonNullElse(text(declared, "texture"), ""));
            final BufferedImage texture = textureId == null ? null : assets.texture(textureId);
            if (texture == null) {
                continue;
            }
            final Vec3[] corners = face.corners(from, to);
            for (int i = 0; i < corners.length; i++) {
                final Vec3 placed = transformation.apply(rotation.apply(corners[i]));
                corners[i] = pose.apply(placed.minus(new Vec3(8, 8, 8)));
            }
            final Vec3 normal = corners[1].minus(corners[0]).cross(corners[2].minus(corners[0]));
            if (normal.length() < 1e-9 || normal.z() <= 0) {
                continue;
            }
            final int tintIndex = declared.get("tintindex") instanceof JsonPrimitive index ? index.getAsInt() : -1;
            final int tint = tintIndex >= 0 && tintIndex < tints.size() ? tints.get(tintIndex) : WHITE;
            raster.quad(corners, face.uv(declared, from, to), texture, tint, shaded ? light(normal.normalised()) : 1);
            drawn = true;
        }
        return drawn;
    }

    /** What is drawn: an item, and the banner patterns its stack carries. */
    private record Stack(String item, List<BannerLayer> patterns) {}

    /** A rectangle of a texture measured on one {@code width} wide. */
    private record Crop(String texture, int width, int x, int y, int w, int h) {}

    /** The inventory's light: the top brightest, the face turned left lighter than the one turned right. */
    private static double light(final Vec3 normal) {
        return Math.min(1, 0.6 + 0.46 * Math.max(0, normal.y()) + 0.28 * Math.max(0, -normal.x()));
    }

    /**
     * A stand-in for a renderer the game draws in code, such as a chest's, a head's or a banner's.
     *
     * The flat base model, the entity's boxes or face, a block texture named like the item, else the particle.
     */
    private boolean special(
            final Raster raster, final JsonObject node, final Stack stack, final Transformation transformation) {
        final String item = stack.item();
        final String base = text(node, "base");
        final Model model = base == null ? null : Model.resolve(assets, base);
        if (model != null && model.generated() && layers(raster, model, List.of())) {
            return true;
        }
        final JsonObject renderer = node.get("model") instanceof JsonObject declared ? declared : new JsonObject();
        final String kind = kind(renderer);
        if (kind.equals("chest") && chest(raster, renderer)) {
            return true;
        }
        if ((kind.equals("head") || kind.equals("player_head")) && head(raster, renderer, kind)) {
            return true;
        }
        if (FACES.containsKey(kind) && crop(raster, FACES.get(kind))) {
            return true;
        }
        if (kind.equals("banner")
                && model != null
                && banner(raster, model, text(renderer, "color"), stack.patterns(), transformation)) {
            return true;
        }
        final String path = AssetSource.id(item).substring("minecraft:".length());
        final BufferedImage block = assets.texture("minecraft:block/" + path);
        if (block != null) {
            cube(raster, block);
            return true;
        }
        final String particle = model == null ? null : model.texture("#particle");
        final BufferedImage texture = particle == null ? null : assets.texture(particle);
        if (texture == null) {
            return false;
        }
        raster.flat(texture, WHITE);
        return true;
    }

    /** A head as its mob's face. */
    private boolean head(final Raster raster, final JsonObject renderer, final String kind) {
        final String mob = kind.equals("player_head") ? "player" : text(renderer, "kind");
        final Crop face = mob == null ? null : HEADS.get(mob.toLowerCase(Locale.ROOT));
        return face != null && crop(raster, face);
    }

    /** One rectangle of a texture, drawn flat; the texture may be any multiple of the width it was measured on. */
    private boolean crop(final Raster raster, final Crop crop) {
        final BufferedImage texture = assets.texture(crop.texture());
        if (texture == null) {
            return false;
        }
        final double unit = texture.getWidth() / (double) crop.width();
        final BufferedImage face = texture.getSubimage(
                (int) (crop.x() * unit), (int) (crop.y() * unit), (int) (crop.w() * unit), (int) (crop.h() * unit));
        final int side = Math.max(face.getWidth(), face.getHeight());
        final BufferedImage square = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        square.getGraphics().drawImage(face, (side - face.getWidth()) / 2, (side - face.getHeight()) / 2, null);
        raster.flat(square, WHITE);
        return true;
    }

    /** A chest as the boxes of its entity model, in the chest item's own inventory pose. */
    private boolean chest(final Raster raster, final JsonObject renderer) {
        final String texture = text(renderer, "texture");
        if (texture == null) {
            return false;
        }
        final String id = AssetSource.id(texture);
        final String path = id.substring(id.indexOf(':') + 1);
        final Model model = new Model(
                Map.of("chest", id.substring(0, id.indexOf(':') + 1) + "entity/chest/" + path),
                StandIns.CHEST,
                new Model.Pose(new Vec3(30, 45, 0), Vec3.ZERO, new Vec3(0.625, 0.625, 0.625)),
                false);
        return boxes(raster, model, List.of(), Transformation.NONE);
    }

    /**
     * A banner as the game draws it, its cloth tinted with its dye and each pattern laid over it in its own.
     *
     * The pole, the bar and the cloth are placed by the item's transformation and posed like the base model.
     */
    private boolean banner(
            final Raster raster,
            final Model base,
            final @Nullable String colour,
            final List<BannerLayer> patterns,
            final Transformation transformation) {
        final Model banner = new Model(
                Map.of("pole", "minecraft:entity/banner/banner_base", "flag", "minecraft:entity/banner/base"),
                StandIns.BANNER,
                base.gui(),
                false);
        final int dye = dye(colour);
        if (patterns.isEmpty()) {
            return boxes(raster, banner, List.of(dye), transformation);
        }
        final BufferedImage cloth = patterned(dye, patterns);
        if (cloth == null) {
            return boxes(raster, banner, List.of(dye), transformation);
        }
        final AssetSource withCloth = new AssetSource() {
            @Override
            public byte @Nullable [] bytes(final String path) {
                return assets.bytes(path);
            }

            @Override
            public @Nullable BufferedImage texture(final String id) {
                return id.equals("minecraft:entity/banner/base") ? cloth : assets.texture(id);
            }
        };
        return new IconPainter(withCloth).boxes(raster, banner, List.of(), transformation);
    }

    /** The banner's cloth with every pattern laid over it, each masked by its texture and tinted by its dye. */
    private @Nullable BufferedImage patterned(final int base, final List<BannerLayer> patterns) {
        final BufferedImage cloth = assets.texture("minecraft:entity/banner/base");
        if (cloth == null) {
            return null;
        }
        final BufferedImage result =
                new BufferedImage(cloth.getWidth(), cloth.getHeight(), BufferedImage.TYPE_INT_ARGB);
        lay(result, cloth, base);
        for (final BannerLayer layer : patterns) {
            final BufferedImage mask = assets.texture(layer.texture());
            if (mask != null) {
                lay(result, mask, dye(layer.dye()));
            }
        }
        return result;
    }

    /** Lays {@code mask}, multiplied by {@code tint} and scaled to {@code target}'s size, over what is there. */
    private static void lay(final BufferedImage target, final BufferedImage mask, final int tint) {
        for (int y = 0; y < target.getHeight(); y++) {
            for (int x = 0; x < target.getWidth(); x++) {
                final int texel =
                        mask.getRGB(x * mask.getWidth() / target.getWidth(), y * mask.getHeight() / target.getHeight());
                final int alpha = texel >>> 24;
                if (alpha == 0) {
                    continue;
                }
                final int red = ((texel >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
                final int green = ((texel >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
                final int blue = (texel & 0xFF) * (tint & 0xFF) / 255;
                target.setRGB(x, y, over(alpha << 24 | red << 16 | green << 8 | blue, target.getRGB(x, y)));
            }
        }
    }

    /** {@code top} laid over {@code bottom}, both as ARGB. */
    private static int over(final int top, final int bottom) {
        final int alpha = top >>> 24;
        final int below = bottom >>> 24;
        final double outAlpha = alpha / 255.0 + below / 255.0 * (1 - alpha / 255.0);
        if (outAlpha == 0) {
            return 0;
        }
        int result = (int) Math.round(outAlpha * 255) << 24;
        for (int shift = 16; shift >= 0; shift -= 8) {
            final double mixed = (((top >> shift) & 0xFF) * (alpha / 255.0)
                            + ((bottom >> shift) & 0xFF) * (below / 255.0) * (1 - alpha / 255.0))
                    / outAlpha;
            result |= (int) Math.round(mixed) << shift;
        }
        return result;
    }

    /** A dye's colour as an opaque tint; white for a name no dye has. */
    private static int dye(final @Nullable String colour) {
        final Integer dye = colour == null ? null : DYES.get(colour.toLowerCase(Locale.ROOT));
        return dye == null ? WHITE : 0xFF000000 | dye;
    }

    /** A full block of one texture in the GUI pose of {@code block/block}. */
    private void cube(final Raster raster, final BufferedImage texture) {
        final Model.Pose pose = new Model.Pose(new Vec3(30, 225, 0), Vec3.ZERO, new Vec3(0.625, 0.625, 0.625));
        final Vec3 from = Vec3.ZERO;
        final Vec3 to = new Vec3(16, 16, 16);
        for (final Face face : Face.values()) {
            final Vec3[] corners = face.corners(from, to);
            for (int i = 0; i < corners.length; i++) {
                corners[i] = pose.apply(corners[i].minus(new Vec3(8, 8, 8)));
            }
            final Vec3 normal = corners[1].minus(corners[0]).cross(corners[2].minus(corners[0]));
            if (normal.z() > 0) {
                raster.quad(corners, face.uv(new JsonObject(), from, to), texture, WHITE, light(normal.normalised()));
            }
        }
    }

    /** The default colour of each tint a model's faces or layers name by index. */
    private List<Integer> tints(final JsonObject node) {
        final List<Integer> tints = new ArrayList<>();
        if (node.get("tints") instanceof JsonArray declared) {
            for (final JsonElement tint : declared) {
                tints.add(tint instanceof JsonObject source ? tint(source) : WHITE);
            }
        }
        return tints;
    }

    private int tint(final JsonObject source) {
        final String type = kind(source);
        if (type.equals("grass") || type.equals("foliage")) {
            final BufferedImage map = assets.texture("minecraft:colormap/" + type);
            if (map != null) {
                final double temperature = Math.clamp(number(source, "temperature", 0.5), 0, 1);
                final double downfall = Math.clamp(number(source, "downfall", 1), 0, 1) * temperature;
                final int x = (int) ((1 - temperature) * (map.getWidth() - 1));
                final int y = (int) ((1 - downfall) * (map.getHeight() - 1));
                return 0xFF000000 | map.getRGB(x, y);
            }
        }
        for (final String member : List.of("value", "default")) {
            if (source.get(member) instanceof JsonPrimitive colour) {
                return 0xFF000000 | colour.getAsInt();
            }
        }
        return WHITE;
    }

    private static double number(final JsonObject object, final String member, final double absent) {
        return object.get(member) instanceof JsonPrimitive value ? value.getAsDouble() : absent;
    }

    /** A node's {@code type} without the {@code minecraft:} namespace, empty when it has none. */
    private static String kind(final JsonObject node) {
        final String type = text(node, "type");
        return type == null ? "" : type.replace("minecraft:", "");
    }

    private static @Nullable String text(final JsonObject object, final String member) {
        return object.get(member) instanceof JsonPrimitive value ? value.getAsString() : null;
    }
}
