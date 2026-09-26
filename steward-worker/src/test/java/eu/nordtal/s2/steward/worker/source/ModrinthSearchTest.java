package eu.nordtal.s2.steward.worker.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.steward.worker.http.FakeHttp;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Modrinth#search} against the payload the live API returned on 2026-09-19
 * (season-2-ops/129).
 *
 * <p>The assertion that carries the weight is the one about the facets. A search that sends them
 * wrong does not fail: it answers plugins for every platform and every Minecraft version, and the
 * interface then offers an admin a Fabric mod to install on a Paper server. There is no symptom
 * until the jar is in the folder.</p>
 */
class ModrinthSearchTest {

    private static final String MC = "26.2";
    private static final String FIXTURE = "modrinth-search-paper.json";

    @Test
    @DisplayName("the hits carry what the interface draws: id, slug, title, icon, page and downloads")
    void readsAHit() throws IOException {
        final Modrinth modrinth = new Modrinth(new FakeHttp().serving("/v2/search", FIXTURE));

        final List<Modrinth.Hit> hits = modrinth.search("worldedit", MC, "paper");

        final Modrinth.Hit first = hits.getFirst();
        assertEquals("1u6JkXh5", first.projectId(), "the id is the identity, not the slug");
        assertEquals("worldedit", first.slug());
        assertEquals("WorldEdit", first.title());
        // The link is built from the slug rather than taken from the payload - the payload has no
        // such field, and a page URL nobody composed is one nobody can be sure of.
        assertEquals("https://modrinth.com/plugin/worldedit", first.pageUrl());
        assertTrue(
                first.iconUrl().startsWith("https://cdn.modrinth.com/"),
                "the thumbnail is on the CDN the interface's policy has to allow");
        assertTrue(first.downloads() > 0);
    }

    @Test
    @DisplayName("the loader is a categories facet and the version a versions facet, both ANDed")
    void sendsTheDocumentedFacets() throws IOException {
        final FakeHttp http = new FakeHttp().serving("/v2/search", FIXTURE);

        new Modrinth(http).search("worldedit", MC, "velocity");

        final String asked = URLDecoder.decode(http.requested().getFirst().toString(), StandardCharsets.UTF_8);
        // Modrinth's own shape: an array of arrays, AND between the outer entries. The loader is
        // a CATEGORY here and a `loaders` filter on the version endpoint - the two endpoints spell
        // it differently and getting it wrong is an empty answer rather than an error.
        assertTrue(
                asked.contains("facets=[[\"categories:velocity\"],[\"versions:26.2\"]," + "[\"project_type:plugin\"]]"),
                asked);
        assertTrue(asked.contains("query=worldedit"), asked);
    }

    @Test
    @DisplayName("an empty query is a search, not an error - it is what an empty box should show")
    void blankIsAllowed() throws IOException {
        final FakeHttp http = new FakeHttp().serving("/v2/search", FIXTURE);

        new Modrinth(http).search("  ", MC, "paper");

        assertTrue(
                URLDecoder.decode(http.requested().getFirst().toString(), StandardCharsets.UTF_8)
                        .contains("query=&"),
                http.requested().getFirst().toString());
    }

    @Test
    @DisplayName("an answer with no hits array is refused rather than read as nothing found")
    void refusesAnAnswerOfTheWrongShape() {
        final Modrinth modrinth = new Modrinth(new FakeHttp().answering("/v2/search", "{}"));

        // "Nothing found" and "the API changed shape" must not look the same on screen: the first
        // means type something else, the second means this feature is broken.
        assertThrows(IOException.class, () -> modrinth.search("x", MC, "paper"));
    }
}
