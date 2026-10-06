package eu.nordtal.season.steward;

import eu.nordtal.season.messages.Tone;
import java.nio.file.Path;

/**
 * Writes the frontend's brand blue from {@link Tone#BRAND}, the one value the game and the bot paint too.
 * Run by {@code :steward:generateApiTypes}; {@code BrandCssTest} fails while the committed file differs.
 */
final class BrandCss {

    /** Where {@code index.css} imports it, relative to the steward module. */
    static final Path TARGET = Path.of("frontend/src/brand.gen.css");

    private BrandCss() {}

    /** The stylesheet, one custom property. */
    static String render() {
        return "/* The brand blue, Tone.BRAND; written by `./gradlew :steward:generateApiTypes`. */\n"
                + ":root {\n  --brand: " + Tone.BRAND.hex() + ";\n}\n";
    }
}
