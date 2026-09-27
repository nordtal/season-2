package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * If Caddy ever grows a Content-Security-Policy, it has to allow Modrinth's image CDN.
 *
 * Why this is a conditional and not an assertion that the header exists
 * Because there is no policy today, and putting one in front of this interface is a separate
 * decision this test does not make: the frontend is a Vite build with inline styles
 * and a service worker, and a policy written to satisfy a plugin thumbnail would be one nobody
 * measured against the rest of the page. What is this test's to guard against is the trap that
 * comes with a policy at all - the browser loads plugin icons from
 * {@code cdn.modrinth.com} itself, and a policy added later without that host does
 * not fail loudly. The images go blank, every other part of the page keeps working, and the only
 * report is in a console somebody has to open.
 *
 * So: no policy passes, and a policy naming the host passes. A policy that forgot it is the one
 * state this file exists to turn into a red build, on the commit that introduces it rather than on
 * the day somebody notices the squares are empty.
 */
class CaddyPolicyTest {

    private static final Path COMPOSE = Path.of("..", "compose.yml");

    /**
     * The Caddyfile out of {@code compose.yml}, and nothing else in the file.
     *
     * Not the whole file, and that is not tidiness. {@code cdn.modrinth.com} already
     * appears in compose.yml on the {@code smp} service - two datapack URLs are hosted there - so
     * a search over the whole document finds the host for a Caddyfile that has never heard of it.
     * That is exactly a test which cannot fail, and it was one until this method existed.
     */
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
            // No policy is allowed: the thumbnails load, which is the only thing this test is about.
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

        // The comment reaches somebody before they write the header; the assertion above only catches it after.
        assertTrue(
                compose.contains(CDN),
                "the Caddyfile in compose.yml no longer mentions " + CDN + ". That note is what"
                        + " tells whoever adds a Content-Security-Policy that the plugin"
                        + " thumbnails depend on it.");
    }
}
