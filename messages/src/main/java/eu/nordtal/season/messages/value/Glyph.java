package eu.nordtal.season.messages.value;

import java.util.Objects;

/**
 * A glyph of the resource pack by its name, drawn in Minecraft and left out of plain text.
 *
 * @param name the name {@code <glyph:name>} would draw
 */
public record Glyph(String name) {

    public Glyph {
        Objects.requireNonNull(name, "name");
    }
}
