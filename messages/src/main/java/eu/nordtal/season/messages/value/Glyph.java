package eu.nordtal.season.messages.value;

import java.util.Objects;

/**
 * A glyph of the resource pack. Minecraft draws it, plain text leaves it out.
 *
 * @param name the name {@code <glyph:name>} draws
 */
public record Glyph(String name) {

    public Glyph {
        Objects.requireNonNull(name, "name");
    }
}
