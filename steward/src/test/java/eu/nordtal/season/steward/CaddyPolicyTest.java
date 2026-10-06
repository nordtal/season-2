package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * What the public Steward site sends: four security headers, and the image CDN in a policy that restricts images.
 *
 * Without the CDN a restricting policy blanks the plugin icons silently; a policy with no image directive needs none.
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

    /** The Caddyfile of {@code compose.yml} without its comments, in lower case. */
    private static String directives() throws IOException {
        return withoutComments(caddyfileIn(Files.readString(COMPOSE, StandardCharsets.UTF_8)))
                .toLowerCase(Locale.ROOT);
    }

    /** The value of the Content-Security-Policy line of the site block, or none. */
    private static String policy(final String directives) {
        return directives
                .lines()
                .map(String::strip)
                .filter(line -> line.startsWith("content-security-policy"))
                .findFirst()
                .orElse("");
    }

    @Test
    void theSiteSendsHstsNosniffAReferrerPolicyAndFrameAncestors() throws IOException {
        final String directives = directives();

        assertTrue(directives.contains("strict-transport-security \"max-age="), "HSTS is sent");
        assertTrue(directives.contains("x-content-type-options nosniff"), "a response is never sniffed");
        assertTrue(directives.contains("referrer-policy no-referrer"), "no referrer leaves the site");
        assertTrue(
                policy(directives).contains("frame-ancestors 'none'"),
                "no other site may frame the admin console: " + policy(directives));
    }

    @Test
    void aPolicyThatRestrictsImagesNamesTheImageCdn() throws IOException {
        final String policy = policy(directives());

        final boolean restrictsImages = policy.contains("img-src") || policy.contains("default-src");

        assertTrue(
                !restrictsImages || policy.contains(CDN),
                "compose.yml sets a Content-Security-Policy that restricts images and does not name " + CDN + "."
                        + " The plugin thumbnails on every service page are loaded from that host by the browser,"
                        + " and a policy without it does not fail: the pictures silently stay blank and only a reader"
                        + " of the browser console finds out. Add it to img-src.");
    }

    @Test
    void theCaddyfileCarriesTheWarning() throws IOException {
        final String compose = caddyfileIn(Files.readString(COMPOSE, StandardCharsets.UTF_8));

        // The comment reaches somebody before they write the header; the assertion only catches it after.
        assertTrue(
                compose.contains(CDN),
                "the Caddyfile in compose.yml no longer mentions " + CDN + ". That note is what"
                        + " tells whoever adds an image directive to the Content-Security-Policy that the plugin"
                        + " thumbnails depend on it.");
    }
}
