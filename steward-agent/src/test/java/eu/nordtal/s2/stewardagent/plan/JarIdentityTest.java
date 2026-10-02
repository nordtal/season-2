package eu.nordtal.s2.stewardagent.plan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.http.HttpFailure;
import eu.nordtal.s2.stewardagent.source.FakeHttp;
import eu.nordtal.s2.stewardagent.source.Modrinth;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The preinstalled jars get their name, picture and link from the project Modrinth published them under. */
class JarIdentityTest {

    @TempDir
    Path plugins;

    private static final String VOICECHAT_PROJECT = """
            [{"id":"9eGKb6K1","slug":"simple-voice-chat","title":"Simple Voice Chat",
              "icon_url":"https://cdn.modrinth.com/data/9eGKb6K1/icon.png"}]""";

    private Installation.Jar jar(final String name, final String content) throws IOException {
        return new Installation.Jar(Files.writeString(plugins.resolve(name), content), name);
    }

    private static String sha512(final String content) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(content.getBytes(UTF_8)));
    }

    private static HttpFailure notFound() {
        return new HttpFailure(URI.create("https://api.modrinth.com/v2/version_file/x"), 404, "");
    }

    @Test
    void aJarModrinthPublishedGetsItsProjectOneItNeverPublishedGetsNothing() throws Exception {
        final Installation.Jar voicechat = jar("voicechat-bukkit-2.6.24.jar", "voicechat");
        final Installation.Jar tags = jar("papermc-display-tags-2.2.0.jar", "tags");
        final FakeHttp http = new FakeHttp()
                .answering("/version_file/" + sha512("voicechat"), "{\"project_id\":\"9eGKb6K1\"}")
                .failing("/version_file/" + sha512("tags"), notFound())
                .answering("/v2/projects", VOICECHAT_PROJECT);

        final Map<String, Modrinth.Project> found =
                new JarIdentity(new Modrinth(http)).identify(List.of(voicechat, tags));

        assertEquals(1, found.size(), found.toString());
        final Modrinth.Project project = found.get("voicechat-bukkit-2.6.24.jar");
        assertEquals("Simple Voice Chat", project.title());
        assertEquals("https://modrinth.com/plugin/simple-voice-chat", project.pageUrl());
        assertEquals("https://cdn.modrinth.com/data/9eGKb6K1/icon.png", project.iconUrl());
    }

    @Test
    void theSecondListAsksModrinthNothingAndADayLaterOnlyForTheNames() throws Exception {
        final Installation.Jar voicechat = jar("voicechat-bukkit-2.6.24.jar", "voicechat");
        final Installation.Jar tags = jar("papermc-display-tags-2.2.0.jar", "tags");
        final FakeHttp http = new FakeHttp()
                .answering("/version_file/" + sha512("voicechat"), "{\"project_id\":\"9eGKb6K1\"}")
                .failing("/version_file/" + sha512("tags"), notFound())
                .answering("/v2/projects", VOICECHAT_PROJECT);
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-24T00:00:00Z"));
        final JarIdentity identity = new JarIdentity(new Modrinth(http), now::get);

        identity.identify(List.of(voicechat, tags));
        final int first = http.requested().size();
        identity.identify(List.of(voicechat, tags));
        assertEquals(first, http.requested().size(), "a known answer, 404 included, is not asked again");

        now.set(now.get().plus(JarIdentity.PROJECT_TTL).plusSeconds(1));
        identity.identify(List.of(voicechat, tags));
        assertEquals(first + 1, http.requested().size());
        assertTrue(http.requested().getLast().toString().contains("/v2/projects"));
    }

    @Test
    void modrinthUnreachableNothingIdentifiedNothingThrownAskedAgainNextTime() throws Exception {
        final Installation.Jar voicechat = jar("voicechat-bukkit-2.6.24.jar", "voicechat");
        final FakeHttp http = new FakeHttp().failing("/version_file/", new IOException("timed out"));
        final JarIdentity identity = new JarIdentity(new Modrinth(http));

        assertTrue(identity.identify(List.of(voicechat)).isEmpty());
        identity.identify(List.of(voicechat));
        assertEquals(2, http.requested().size(), "a failure is not remembered as an answer");
    }
}
