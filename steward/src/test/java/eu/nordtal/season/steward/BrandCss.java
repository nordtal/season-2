package eu.nordtal.season.steward;

import eu.nordtal.season.messages.Tone;
import java.nio.file.Path;

/**
 * Writes the frontend's brand blue from {@link Tone#BRAND}, which the game and the bot paint too.
 *
 * {@code :steward:generateApiTypes} runs it. {@code BrandCssTest} fails while the committed file differs.
 */
final class BrandCss {

    static final Path TARGET = Path.of("frontend/src/brand.gen.css");

    private BrandCss() {}

    static String render() {
        return "/* The brand blue, Tone.BRAND; written by `./gradlew :steward:generateApiTypes`. */\n"
                + ":root {\n  --brand: " + Tone.BRAND.hex() + ";\n}\n";
    }
}
