package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.ComposeFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Checks the security headers of the public Steward site and the resources its policy allows. */
class CaddyPolicyTest {

    private static String directives() {
        return ComposeFile.get()
                .configContent("caddyfile")
                .lines()
                .map(String::strip)
                .filter(line -> !line.startsWith("#"))
                .collect(Collectors.joining("\n"));
    }

    private static String policy(final String directives) {
        return directives
                .lines()
                .map(String::strip)
                .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("content-security-policy"))
                .findFirst()
                .orElse("");
    }

    @Test
    void theSiteSendsHstsNosniffAReferrerPolicyAndFrameAncestors() {
        final String directives = directives().toLowerCase(Locale.ROOT);

        assertTrue(directives.contains("strict-transport-security \"max-age="), "HSTS is sent");
        assertTrue(directives.contains("x-content-type-options nosniff"), "a response is never sniffed");
        assertTrue(directives.contains("referrer-policy no-referrer"), "no referrer leaves the site");
        assertTrue(
                policy(directives).contains("frame-ancestors 'none'"),
                "no other site may frame the admin console: " + policy(directives));
    }

    @Test
    void thePolicyRestrictsScriptsAndConnectionsToSteward() {
        final String policy = policy(directives());

        assertTrue(policy.contains("default-src 'self'"), policy);
        assertTrue(policy.contains("script-src 'self'"), policy);
        assertTrue(policy.contains("object-src 'none'"), policy);
    }

    @Test
    void thePolicyAllowsTheStartupScriptInTheServedPage() throws IOException, NoSuchAlgorithmException {
        try (final var resource = Objects.requireNonNull(getClass().getResourceAsStream("/web/index.html"))) {
            final String page = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            final var inline =
                    Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL).matcher(page);
            assertTrue(inline.find());
            final byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(inline.group(1).getBytes(StandardCharsets.UTF_8));
            final String hash = Base64.getEncoder().encodeToString(digest);
            final String policy = policy(directives());
            assertTrue(policy.contains("'sha256-" + hash + "'"));
        }
    }

    @Test
    void theImagePolicyAllowsHttpsMediaInDocumentPreviews() {
        final String policy = policy(directives());
        assertTrue(policy.contains("img-src 'self' data: blob: https:;"), policy);
    }
}
