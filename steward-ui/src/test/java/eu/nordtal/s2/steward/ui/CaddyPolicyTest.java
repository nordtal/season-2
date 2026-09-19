package eu.nordtal.s2.steward.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * If Caddy ever grows a Content-Security-Policy, it has to allow Modrinth's image CDN.
 *
 * <h2>Why this is a conditional and not an assertion that the header exists</h2>
 * Because there is no policy today, and putting one in front of this interface is a separate
 * decision that is not season-2-ops/129's to take: the frontend is a Vite build with inline styles
 * and a service worker, and a policy written to satisfy a plugin thumbnail would be one nobody
 * measured against the rest of the page. What <em>is</em> this ticket's to take is the trap that
 * comes with the decision Till did make - the browser loads plugin icons from
 * {@code cdn.modrinth.com} itself (2026-09-19), and a policy added later without that host does
 * not fail loudly. The images go blank, every other part of the page keeps working, and the only
 * report is in a console somebody has to open.
 *
 * <p>So: no policy passes, and a policy naming the host passes. A policy that forgot it is the one
 * state this file exists to turn into a red build, on the commit that introduces it rather than on
 * the day somebody notices the squares are empty.</p>
 */
class CaddyPolicyTest {

    private static final Path COMPOSE = Path.of("..", "compose.yml");

    /**
     * The Caddyfile out of {@code compose.yml}, and nothing else in the file.
     *
     * <p><b>Not the whole file, and that is not tidiness.</b> {@code cdn.modrinth.com} already
     * appears in compose.yml on the {@code smp} service - two datapack URLs are hosted there - so
     * a search over the whole document finds the host for a Caddyfile that has never heard of it.
     * That is exactly a test which cannot fail, and it was one until this method existed.</p>
     */
    private static String caddyfileIn(final String compose) {
        final int block = compose.indexOf("caddyfile:");
        return block < 0 ? "" : compose.substring(block);
    }

    /** Every line whose first non-blank character is a {@code #}, gone. */
    private static String withoutComments(final String text) {
        return text.lines().filter(line -> !line.strip().startsWith("#"))
                .reduce(new StringBuilder(), (builder, line) -> builder.append(line).append('\n'),
                        StringBuilder::append)
                .toString();
    }

    /** Where Modrinth serves every project icon from, checked against the live API 2026-09-19. */
    private static final String CDN = "cdn.modrinth.com";

    @Test
    @DisplayName("a Content-Security-Policy in the Caddyfile, if there is one, allows Modrinth's CDN")
    void policyAllowsTheImageCdn() throws IOException {
        // WITHOUT THE COMMENTS, and that is the whole reason this method exists in this shape.
        // The Caddyfile carries a note naming cdn.modrinth.com (the second test holds it there),
        // so a search over the raw file would find the host inside the warning and pass for a
        // policy that does not name it at all - a test that cannot fail. Comment lines are
        // therefore removed before anything is looked for.
        final String compose =
                withoutComments(caddyfileIn(Files.readString(COMPOSE, StandardCharsets.UTF_8)));

        // The header can be spelled by Caddy's own `header` directive or inside a `header {}`
        // block, and in either case the name is what is searched for. Lowercased so that neither
        // spelling of the header name can slip past.
        final String lower = compose.toLowerCase(Locale.ROOT);
        final int directive = lower.indexOf("content-security-policy");
        if (directive < 0) {
            // No policy. That is the state as of 2026-09-19 and it is allowed - the thumbnails
            // load, which is the only thing this test is about.
            return;
        }

        assertTrue(lower.contains(CDN),
                "compose.yml sets a Content-Security-Policy and does not name " + CDN + " in it."
                        + " The plugin thumbnails on every service page (season-2-ops/129) are"
                        + " loaded from that host by the browser, and a policy without it does not"
                        + " fail - the pictures silently stay blank and only a reader of the"
                        + " browser console finds out. Add it to img-src.");
    }

    @Test
    @DisplayName("and the Caddyfile says so out loud, so the next person to add one reads it first")
    void theCaddyfileCarriesTheWarning() throws IOException {
        final String compose = caddyfileIn(Files.readString(COMPOSE, StandardCharsets.UTF_8));

        // The comment is the thing that reaches somebody BEFORE they write the header - the
        // assertion above only catches them afterwards, and only if they run this module's tests.
        assertTrue(compose.contains(CDN),
                "the Caddyfile in compose.yml no longer mentions " + CDN + ". That note is what"
                        + " tells whoever adds a Content-Security-Policy that the plugin"
                        + " thumbnails depend on it (season-2-ops/129).");
    }
}
