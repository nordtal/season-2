package eu.nordtal.season.steward.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.steward.messages.TranslationSearch.Candidate;
import eu.nordtal.season.steward.messages.TranslationSearch.Field;
import eu.nordtal.season.steward.messages.TranslationSearch.Hit;
import eu.nordtal.season.steward.messages.TranslationSearch.Kind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests that the search finds a translation by name, key, parent and text in every language, despite typos. */
class TranslationSearchTest {

    private static final List<String> LANGUAGES = List.of("en", "de");

    private static final Candidate PAYMENT = new Candidate(
            Kind.DOCUMENT,
            "discord/payment-link",
            "Payment link",
            "payment-link",
            List.of("Shop"),
            Map.of("en", List.of("Pay {amount} here"), "de", List.of("Zahlungslink für {amount}")));

    private static final Candidate DAYS = new Candidate(
            Kind.TEXT,
            "values/duration.days",
            "Days",
            "duration.days",
            List.of("Durations"),
            Map.of("en", List.of("{n, plural, one {# day} other {# days}}"), "de", List.of("{n} Tage")));

    private static final Candidate PURCHASE = new Candidate(
            Kind.TEXT,
            "smp/shop.bought",
            "Bought",
            "shop.bought",
            List.of("Shop"),
            Map.of("en", List.of("Your purchase is on its way")));

    private static final List<Candidate> ALL = List.of(PAYMENT, DAYS, PURCHASE);

    private static List<String> ids(final List<Hit> hits) {
        return hits.stream().map(Hit::id).toList();
    }

    @Test
    void aTextInAnotherLanguageIsFoundAndOpensInThatLanguage() {
        final List<Hit> hits = TranslationSearch.find("Zahlungslink", ALL, LANGUAGES);

        assertEquals(List.of("discord/payment-link"), ids(hits));
        assertEquals("de", hits.getFirst().language());
        assertEquals(Field.TEXT, hits.getFirst().field());
        assertEquals("Zahlungslink für {amount}", hits.getFirst().text());
    }

    @Test
    void aNameIsFoundInTheFirstLanguageOfTheNetwork() {
        final Hit hit = TranslationSearch.find("payment link", ALL, LANGUAGES).getFirst();

        assertEquals("discord/payment-link", hit.id());
        assertEquals("en", hit.language());
        assertEquals(Field.NAME, hit.field());
        assertTrue(hit.exact());
    }

    @Test
    void aWordOfFourLettersOrMoreToleratesOneTypoAndFromEightTwo() {
        assertEquals(List.of("smp/shop.bought"), ids(TranslationSearch.find("purchse", ALL, LANGUAGES)));
        assertEquals(List.of("discord/payment-link"), ids(TranslationSearch.find("Zahlngslnk", ALL, LANGUAGES)));
        assertEquals(List.of(), ids(TranslationSearch.find("Zhlngslnk", ALL, LANGUAGES)), "three typos are too many");
        assertEquals(List.of(), ids(TranslationSearch.find("dya", ALL, LANGUAGES)), "a short word takes none");
        assertEquals(List.of("values/duration.days"), ids(TranslationSearch.find("dasy", ALL, LANGUAGES)));
    }

    @Test
    void anExactHitRanksAboveAnApproximateOneAndANameAboveAKeyAboveAParent() {
        final Candidate shopName =
                new Candidate(Kind.TEXT, "smp/shop.title", "Shop", "shop.title", List.of(), Map.of());
        final Candidate shopping =
                new Candidate(Kind.TEXT, "smp/shpo", "Shpo", "x", List.of(), Map.of("en", List.of("x")));

        assertEquals(
                List.of("smp/shop.title", "smp/shop.bought", "discord/payment-link", "smp/shpo"),
                ids(TranslationSearch.find("shop", List.of(shopping, PAYMENT, PURCHASE, shopName), LANGUAGES)));
    }

    @Test
    void aWordFoundAtTheStartOfAWordRanksAboveOneFoundInsideAWord() {
        final Candidate stage =
                new Candidate(Kind.TEXT, "steward/run.stage", "A run's stage", "run.stage", List.of(), Map.of());

        assertEquals(
                List.of("values/duration.days", "steward/run.stage"),
                ids(TranslationSearch.find("Tage", List.of(stage, DAYS), LANGUAGES)));
        final Candidate compound =
                new Candidate(Kind.TEXT, "discord/link", "Zahlungslink", "zahlungslink", List.of(), Map.of());
        assertEquals(List.of("discord/link"), ids(TranslationSearch.find("link", List.of(compound), LANGUAGES)));
    }

    @Test
    void aKeyAndAParentFindTheTranslation() {
        assertEquals(
                Field.KEY,
                TranslationSearch.find("duration.days", ALL, LANGUAGES)
                        .getFirst()
                        .field());
        assertEquals(List.of("values/duration.days"), ids(TranslationSearch.find("durations", ALL, LANGUAGES)));
    }

    @Test
    void everyWordHasToBeFound() {
        assertEquals(List.of(), TranslationSearch.find("payment days", ALL, LANGUAGES));
        assertEquals(List.of(), TranslationSearch.find("  ", ALL, LANGUAGES));
    }
}
