package eu.nordtal.season.packbuilder.wheel;

import eu.nordtal.season.packbuilder.json.Json;
import eu.nordtal.season.packbuilder.vanilla.VanillaFacts;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

/**
 * What the wheel reads of vanilla.
 * Each prize's item definition reduced to what the GUI draws, the GUI display transform of each model, and the letters
 * of {@code ascii.png} for the textures the pack draws.
 */
final class WheelVanilla {

    private static final int DEPTH = 16;
    private static final Map<String, Object> EMPTY = Map.of("type", "minecraft:empty");

    private final VanillaFacts facts;
    private final Map<String, Map<?, ?>> models = new HashMap<>();

    WheelVanilla(final VanillaFacts facts) {
        this.facts = facts;
    }

    /** The item definition's model node of {@code key}, an item id without {@code minecraft:}, if vanilla has it. */
    Optional<Map<?, ?>> definition(final String key) {
        return read(VanillaFacts.ITEMS + key + ".json").flatMap(definition -> asMap(definition.get("model")));
    }

    /** The GUI display transform that the model {@code ref} or its nearest parent carries, as vanilla applies it. */
    double[] display(final String ref) {
        return displayMatrix(gui(ref));
    }

    private @Nullable Map<?, ?> gui(final String ref) {
        Map<?, ?> model = model(ref);
        for (int i = 0; i < DEPTH; i++) {
            if (model.get("display") instanceof final Map<?, ?> display
                    && display.get("gui") instanceof final Map<?, ?> gui) {
                return gui;
            }
            if (!(model.get("parent") instanceof final String parent)) {
                return null;
            }
            model = model(parent);
        }
        return null;
    }

    private Map<?, ?> model(final String ref) {
        final String path = VanillaFacts.modelPath(ref);
        return models.computeIfAbsent(path, p -> read(p).orElse(Map.of()));
    }

    private Optional<Map<?, ?>> read(final String path) {
        return facts.file(path).flatMap(bytes -> asMap(Json.parse(bytes)));
    }

    /** Vanilla's {@code ascii.png}, 16 by 16 cells of 8 pixels. */
    BufferedImage ascii() {
        final byte[] bytes = facts.file(VanillaFacts.ASCII)
                .orElseThrow(() -> new IllegalStateException("the vanilla facts have no " + VanillaFacts.ASCII));
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code ItemTransform.apply} for the GUI: translate, rotate X, Y, Z, scale, then vanilla's shift by -0.5. */
    static double[] displayMatrix(final @Nullable Map<?, ?> gui) {
        final double[] shift = WheelMath.translate(-0.5, -0.5, -0.5);
        if (gui == null) {
            return shift;
        }
        final double[] t = three(gui, "translation", 0.0);
        final double[] r = three(gui, "rotation", 0.0);
        final double[] s = three(gui, "scale", 1.0);
        for (int i = 0; i < 3; i++) {
            t[i] = Math.clamp(t[i] * 0.0625, -5.0, 5.0);
            r[i] = Math.toRadians(r[i]);
            s[i] = Math.clamp(s[i], -4.0, 4.0);
        }
        return WheelMath.times(
                WheelMath.translate(t[0], t[1], t[2]),
                WheelMath.rotateX(r[0]),
                WheelMath.rotateY(r[1]),
                WheelMath.rotateZ(r[2]),
                WheelMath.scale(s[0], s[1], s[2]),
                shift);
    }

    private static double[] three(final Map<?, ?> gui, final String key, final double fallback) {
        if (gui.get(key) instanceof final List<?> list) {
            final double[] out = new double[list.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = ((Number) list.get(i)).doubleValue();
            }
            return out;
        }
        return new double[] {fallback, fallback, fallback};
    }

    /**
     * The definition {@code node} reduced to what a GUI slot shows of a plain stack.
     * A display context select goes to its {@code gui} case, any other select and a range to its fallback or first
     * case, a condition to its false side. A collapsed node's transformation moves onto its child.
     */
    static Map<String, Object> leaf(final Map<?, ?> node) {
        final String type = shortId((String) node.get("type"));
        final Object own = present(node.get("transformation"));
        final @Nullable Map<?, ?> child = switch (type) {
            case "select" -> selected(node);
            case "range_dispatch" -> ranged(node);
            case "condition" -> (Map<?, ?>) node.get("on_false");
            default -> null;
        };
        if (child != null) {
            final Map<String, Object> reduced = leaf(child);
            if (own == null) {
                return reduced;
            }
            final Object inner = present(reduced.get("transformation"));
            final double[] below = inner == null ? WheelMath.identity() : WheelMath.transformation(inner);
            reduced.put("transformation", WheelMath.list(WheelMath.times(WheelMath.transformation(own), below)));
            return reduced;
        }
        final Map<String, Object> out = copy(node);
        if (type.equals("composite")) {
            final List<Object> models = new ArrayList<>();
            for (final Object model : (List<?>) node.get("models")) {
                models.add(leaf((Map<?, ?>) model));
            }
            out.put("models", models);
        }
        return out;
    }

    private static Map<?, ?> selected(final Map<?, ?> node) {
        final List<?> cases = (List<?>) node.get("cases");
        if (shortId((String) node.get("property")).equals("display_context")) {
            for (final Object c : cases) {
                final Map<?, ?> each = (Map<?, ?>) c;
                if (whens(each).contains("gui")) {
                    if (each.get("model") instanceof final Map<?, ?> gui) {
                        return gui;
                    }
                    break;
                }
            }
        }
        if (node.get("fallback") instanceof final Map<?, ?> fallback) {
            return fallback;
        }
        if (!cases.isEmpty() && ((Map<?, ?>) cases.getFirst()).get("model") instanceof final Map<?, ?> first) {
            return first;
        }
        return EMPTY;
    }

    private static Map<?, ?> ranged(final Map<?, ?> node) {
        if (node.get("fallback") instanceof final Map<?, ?> fallback) {
            return fallback;
        }
        final List<?> entries = (List<?>) node.get("entries");
        if (!entries.isEmpty()
                && entries.getFirst() instanceof final Map<?, ?> entry
                && entry.get("model") instanceof final Map<?, ?> first) {
            return first;
        }
        return EMPTY;
    }

    /** The model refs of {@code leaf} whose display transforms place it: a model's own, a special model's base. */
    static List<String> refs(final Map<?, ?> leaf) {
        return switch (shortId((String) leaf.get("type"))) {
            case "model" -> List.of((String) leaf.get("model"));
            case "special" -> List.of((String) leaf.get("base"));
            case "composite" -> {
                final List<String> out = new ArrayList<>();
                for (final Object model : (List<?>) leaf.get("models")) {
                    out.addAll(refs((Map<?, ?>) model));
                }
                yield out;
            }
            default -> List.of();
        };
    }

    /**
     * {@code leaf} with every model moved by the screen move {@code move}, each by its own display transform.
     * {@code above} is the product of the transformations above the node.
     */
    Map<String, Object> moved(final Map<?, ?> leaf, final double[] move, final double[] above) {
        final Map<String, Object> out = copy(leaf);
        final Object transformation = present(leaf.get("transformation"));
        final double[] own = transformation == null ? WheelMath.identity() : WheelMath.transformation(transformation);
        switch (shortId((String) leaf.get("type"))) {
            case "model", "special" -> {
                final List<String> refs = refs(leaf);
                if (refs.size() != 1) {
                    throw new IllegalStateException("a model node with " + refs.size() + " refs");
                }
                final double[] local =
                        WheelMath.times(WheelMath.inverse(above), local(display(refs.getFirst()), move), above, own);
                out.put("transformation", WheelMath.list(local));
            }
            case "composite" -> {
                final double[] here = WheelMath.times(above, own);
                final List<Object> models = new ArrayList<>();
                for (final Object model : (List<?>) leaf.get("models")) {
                    models.add(moved((Map<?, ?>) model, move, here));
                }
                out.put("models", models);
            }
            default -> {}
        }
        return out;
    }

    /** L = D^-1 M D: the transformation under the display transform that moves the drawn item by {@code move}. */
    static double[] local(final double[] display, final double[] move) {
        return WheelMath.times(WheelMath.inverse(display), move, display);
    }

    private static List<?> whens(final Map<?, ?> c) {
        final Object when = c.get("when");
        return when instanceof final List<?> list ? list : Collections.singletonList(when);
    }

    private static Optional<Map<?, ?>> asMap(final @Nullable Object value) {
        return value instanceof final Map<?, ?> map ? Optional.of(map) : Optional.empty();
    }

    static String shortId(final String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static @Nullable Object present(final @Nullable Object value) {
        return value == Json.NULL ? null : value;
    }

    private static Map<String, Object> copy(final Map<?, ?> node) {
        final Map<String, Object> out = new LinkedHashMap<>();
        node.forEach((k, v) -> out.put((String) k, v));
        return out;
    }

    /** A glyph of {@code ascii}: its rows of ink and its advance, vanilla's width plus one. */
    record Glyph(boolean[][] rows, int advance) {}

    /** The glyph for {@code c}. */
    static Glyph glyph(final BufferedImage ascii, final char c) {
        final int cell = ascii.getWidth() / 16;
        final int gx = (c % 16) * cell;
        final int gy = (c / 16) * cell;
        final boolean[][] rows = new boolean[cell][cell];
        int width = 0;
        for (int y = 0; y < cell; y++) {
            for (int x = 0; x < cell; x++) {
                rows[y][x] = (ascii.getRGB(gx + x, gy + y) >>> 24) != 0;
                if (rows[y][x]) {
                    width = Math.max(width, x + 1);
                }
            }
        }
        return new Glyph(rows, c == ' ' ? 4 : width + 1);
    }
}
