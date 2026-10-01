package eu.nordtal.s2.steward.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.steward.http.FakeHttp;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link Modrinth#search} against the payload the live API returns.
 *
 * Wrong facets do not fail, they offer plugins for every platform, so the facets carry the weight.
 */
class ModrinthSearchTest {

    private static final String MC = "26.2";
    private static final String FIXTURE = "modrinth-search-paper.json";

    @Test
    void theHitsCarryWhatTheInterfaceDrawsIdSlugTitleIconPageAndDownloads() throws IOException {
        final Modrinth modrinth = new Modrinth(new FakeHttp().serving("/v2/search", FIXTURE));

        final List<Modrinth.Hit> hits = modrinth.search("worldedit", MC, "paper");

        final Modrinth.Hit first = hits.getFirst();
        assertEquals("1u6JkXh5", first.projectId(), "the id is the identity, not the slug");
        assertEquals("worldedit", first.slug());
        assertEquals("WorldEdit", first.title());
        // The link is built from the slug, since the payload has no such field.
        assertEquals("https://modrinth.com/plugin/worldedit", first.pageUrl());
        assertTrue(
                first.iconUrl().startsWith("https://cdn.modrinth.com/"),
                "the thumbnail is on the CDN the interface's policy has to allow");
        assertTrue(first.downloads() > 0);
    }

    @Test
    void theLoaderIsACategoriesFacetAndTheVersionAVersionsFacetBothAnded() throws IOException {
        final FakeHttp http = new FakeHttp().serving("/v2/search", FIXTURE);

        new Modrinth(http).search("worldedit", MC, "velocity");

        final String asked = URLDecoder.decode(http.requested().getFirst().toString(), StandardCharsets.UTF_8);
        // The loader is a CATEGORY here, not a `loaders` filter; getting it wrong is empty, not an error.
        assertTrue(
                asked.contains("facets=[[\"categories:velocity\"],[\"versions:26.2\"]," + "[\"project_type:plugin\"]]"),
                asked);
        assertTrue(asked.contains("query=worldedit"), asked);
    }

    @Test
    void anEmptyQueryIsASearchNotAnErrorItIsWhatAnEmptyBoxShouldShow() throws IOException {
        final FakeHttp http = new FakeHttp().serving("/v2/search", FIXTURE);

        new Modrinth(http).search("  ", MC, "paper");

        assertTrue(
                URLDecoder.decode(http.requested().getFirst().toString(), StandardCharsets.UTF_8)
                        .contains("query=&"),
                http.requested().getFirst().toString());
    }

    @Test
    void anAnswerWithNoHitsArrayIsRefusedRatherThanReadAsNothingFound() {
        final Modrinth modrinth = new Modrinth(new FakeHttp().answering("/v2/search", "{}"));

        // "Nothing found" and "the API changed shape" must not look the same on screen.
        assertThrows(IOException.class, () -> modrinth.search("x", MC, "paper"));
    }
}
