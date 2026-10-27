package eu.nordtal.season.steward.messages;

import eu.nordtal.season.internalapi.agent.MessageEntry;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.Text;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Finds every text and document by its name, its key, the areas above it and its text in every language.
 *
 * It tolerates typos and searches what the two lists answer, in memory. A building block is a text of its own.
 */
public final class TranslationSearch {

    /** Everything that is neither a letter nor a digit separates words, a key's dots and an ICU brace included. */
    private static final Pattern SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** A word this long takes one typo. */
    private static final int ONE_TYPO = 4;

    /** A word this long takes two typos. */
    private static final int TWO_TYPOS = 8;

    private final MessagesApi messages;
    private final DocumentsApi documents;

    public TranslationSearch(final MessagesApi messages, final DocumentsApi documents) {
        this.messages = messages;
        this.documents = documents;
    }

    public enum Kind {
        TEXT,
        DOCUMENT
    }

    /** Where a word was found, the strongest first. */
    public enum Field {
        NAME,
        KEY,
        PARENT,
        TEXT
    }

    /**
     * One translation as the search reads it.
     *
     * @param id      {@code bundle/key} of a text, {@code namespace/name} of a document
     * @param parents the names of the areas above it, outermost first
     * @param texts   its versions by language
     */
    record Candidate(
            Kind kind, String id, String name, String key, List<String> parents, Map<String, List<String>> texts) {}

    /**
     * One translation the query finds.
     *
     * @param language the language it was found in, the network's first where no text of one was
     * @param field    the weakest field a word of the query was found in
     * @param exact    whether every word was found as typed at the start of a word
     * @param text     the version that was found, or the first in {@code language}, as written
     */
    public record Hit(Kind kind, String id, String language, Field field, boolean exact, String text) {}

    /** {@code GET /api/translation-search?query=} answers the hits, the best first. */
    public void serve(final Context ctx) {
        final DocumentsApi.DocumentList listed = documents.documents();
        final List<Candidate> candidates = new ArrayList<>();
        messages.current().forEach(text -> candidates.add(candidateOf(text.entry())));
        listed.documents()
                .forEach(view -> candidates.add(candidateOf(
                        view.declaration().id(),
                        view.declaration().title(),
                        view.declaration().name(),
                        view.declaration().area(),
                        Objects.requireNonNullElse(view.written(), view.packaged()))));
        ctx.json(find(Objects.requireNonNullElse(ctx.queryParam("query"), ""), candidates, listed.languages()));
    }

    private static Candidate candidateOf(final MessageEntry entry) {
        final Map<String, List<String>> texts = new LinkedHashMap<>(entry.texts());
        texts.putAll(entry.overrides());
        final String name = entry.name() != null
                ? entry.name()
                : entry.key().substring(entry.key().lastIndexOf('.') + 1);
        return new Candidate(
                Kind.TEXT,
                entry.bundle() + "/" + entry.key(),
                name,
                entry.key(),
                entry.section().stream().filter(Objects::nonNull).toList(),
                texts);
    }

    private static Candidate candidateOf(
            final String id, final String title, final String name, final String area, final Document document) {
        final Map<String, List<String>> texts = new LinkedHashMap<>();
        for (final Text text : document.texts()) {
            text.languages()
                    .forEach((language, versions) -> texts.computeIfAbsent(language, ignored -> new ArrayList<>())
                            .addAll(versions));
        }
        return new Candidate(Kind.DOCUMENT, id, title, name, List.of(area), texts);
    }

    /** Returns the candidates every word of {@code query} finds, each once in its best language, the best first. */
    static List<Hit> find(final String query, final List<Candidate> candidates, final List<String> languages) {
        final List<String> words = wordsOf(query);
        if (words.isEmpty()) {
            return List.of();
        }
        final List<Hit> hits = new ArrayList<>();
        for (final Candidate candidate : candidates) {
            Hit best = null;
            for (final String language : languagesOf(candidate, languages)) {
                final Hit hit = hitOf(candidate, language, words);
                if (hit != null && (best == null || BY_RANK.compare(hit, best) < 0)) {
                    best = hit;
                }
            }
            if (best != null) {
                hits.add(best);
            }
        }
        return hits.stream().sorted(BY_RANK).toList();
    }

    /** Exact first, then by the weakest field. The sort is stable, so equal hits keep the order of the lists. */
    private static final Comparator<Hit> BY_RANK =
            Comparator.comparing((Hit hit) -> !hit.exact()).thenComparing(Hit::field);

    /** The network's languages in order, then any other the candidate has a text in. */
    private static Set<String> languagesOf(final Candidate candidate, final List<String> languages) {
        final Set<String> all = new LinkedHashSet<>(languages);
        all.addAll(candidate.texts().keySet());
        return all;
    }

    private static @Nullable Hit hitOf(final Candidate candidate, final String language, final List<String> words) {
        final List<String> versions = candidate.texts().getOrDefault(language, List.of());
        boolean exact = true;
        Field weakest = Field.NAME;
        String shown = versions.isEmpty() ? "" : versions.getFirst();
        for (final String word : words) {
            final List<Match> matches = new ArrayList<>();
            matches.add(match(word, candidate.name(), Field.NAME, null));
            matches.add(match(word, candidate.key(), Field.KEY, null));
            candidate.parents().forEach(parent -> matches.add(match(word, parent, Field.PARENT, null)));
            versions.forEach(version -> matches.add(match(word, version, Field.TEXT, version)));
            final Match best =
                    matches.stream().filter(Objects::nonNull).min(BEST_MATCH).orElse(null);
            if (best == null) {
                return null;
            }
            exact &= best.exact();
            if (best.field().compareTo(weakest) > 0) {
                weakest = best.field();
            }
            if (best.version() != null) {
                shown = best.version();
            }
        }
        return new Hit(candidate.kind(), candidate.id(), language, weakest, exact, shown);
    }

    /** @param version the text it was found in, or {@code null} for a field that is no text */
    private record Match(
            boolean exact, Field field, @Nullable String version) {}

    private static final Comparator<Match> BEST_MATCH =
            Comparator.comparing((Match match) -> !match.exact()).thenComparing(Match::field);

    /**
     * Returns how {@code word} is found in {@code field}, or {@code null}.
     *
     * Exact at the start of a word; approximate inside a word, such as a compound's end, or with typos at a start.
     */
    private static @Nullable Match match(
            final String word, final String field, final Field where, final @Nullable String version) {
        final List<String> words = wordsOf(field);
        final String joined = " " + String.join(" ", words);
        if (joined.contains(" " + word)) {
            return new Match(true, where, version);
        }
        if (joined.contains(word)) {
            return new Match(false, where, version);
        }
        final int typos = word.length() >= TWO_TYPOS ? 2 : word.length() >= ONE_TYPO ? 1 : 0;
        if (typos == 0) {
            return null;
        }
        for (final String candidate : words) {
            if (distanceToAStart(word, candidate) <= typos) {
                return new Match(false, where, version);
            }
        }
        return null;
    }

    private static List<String> wordsOf(final String text) {
        return SEPARATOR
                .splitAsStream(text.toLowerCase(Locale.ROOT))
                .filter(word -> !word.isEmpty())
                .toList();
    }

    /**
     * Returns the fewest edits that turn {@code word} into a start of {@code in}.
     *
     * A swap of two neighbours counts as one, and a start is enough, so a word still being typed is found.
     */
    static int distanceToAStart(final String word, final String in) {
        final int[][] d = new int[word.length() + 1][in.length() + 1];
        for (int i = 0; i <= word.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= in.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= word.length(); i++) {
            for (int j = 1; j <= in.length(); j++) {
                final int cost = word.charAt(i - 1) == in.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1
                        && j > 1
                        && word.charAt(i - 1) == in.charAt(j - 2)
                        && word.charAt(i - 2) == in.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        int least = Integer.MAX_VALUE;
        for (int j = 0; j <= in.length(); j++) {
            least = Math.min(least, d[word.length()][j]);
        }
        return least;
    }
}
