package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * A Content-Security-Policy in the Caddyfile, if one ever appears, must allow Modrinth's image CDN.
 *
 * Without that host plugin icons go blank silently, so no policy passes and a policy naming it passes.
 */
class CaddyPolicyTest {

    private static final Path COMPOSE = Path.of("..", "compose.yml");

    /** The Caddyfile out of {@code compose.yml} and nothing else, since the {@code smp} service names the CDN too. */
    private static String caddyfileIn(final String compose) {
        final int block = compose.indexOf("caddyfile:");
        return block < 0 ? "" : compose.substring(block);
    }

    /** Every line whose first non-blank character is a {@code #}, gone. */
    private static String withoutComments(final String text) {
        return text.lines()
                .filter(line -> !line.strip().startsWith("#"))
                .reduce(
                        new StringBuilder(),
                        (builder, line) -> builder.append(line).append('\n'),
                        StringBuilder::append)
                .toString();
    }

    /** Where Modrinth serves every project icon from. */
    private static final String CDN = "cdn.modrinth.com";

    @Test
    void policyAllowsTheImageCdn() throws IOException {
        // Comments removed first: the warning itself names the host, which would let a bare search pass wrongly.
        final String compose = withoutComments(caddyfileIn(Files.readString(COMPOSE, StandardCharsets.UTF_8)));

        // The header can be spelled with `header` or inside a `header {}` block; lowercased either way.
        final String lower = compose.toLowerCase(Locale.ROOT);
        final int directive = lower.indexOf("content-security-policy");
        if (directive < 0) {
            // No policy: the thumbnails load.
            return;
        }

        assertTrue(
                lower.contains(CDN),
                "compose.yml sets a Content-Security-Policy and does not name " + CDN + " in it."
                        + " The plugin thumbnails on every service page are"
                        + " loaded from that host by the browser, and a policy without it does not"
                        + " fail - the pictures silently stay blank and only a reader of the"
                        + " browser console finds out. Add it to img-src.");
    }

    @Test
    void theCaddyfileCarriesTheWarning() throws IOException {
        final String compose = caddyfileIn(Files.readString(COMPOSE, StandardCharsets.UTF_8));

        // The comment reaches somebody before they write the header; the assertion only catches it after.
        assertTrue(
                compose.contains(CDN),
                "the Caddyfile in compose.yml no longer mentions " + CDN + ". That note is what"
                        + " tells whoever adds a Content-Security-Policy that the plugin"
                        + " thumbnails depend on it.");
    }
}
