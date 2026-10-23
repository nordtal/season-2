package eu.nordtal.season.database.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.journal.JournalSearch.Term;
import eu.nordtal.season.database.journal.JournalSearch.Term.Kind;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JournalSearchTest {

    private static final Set<String> SERVICES = Set.of("smp", "proxy", "steward-agent");
    private static final Map<JournalAction, String> LABELS = Map.of(
            JournalAction.GRANT_ACCESS, "Access granted",
            JournalAction.REVOKE_ACCESS, "Access revoked",
            JournalAction.GRANT_ADMIN, "Admin granted",
            JournalAction.SERVICE_DOWN, "Service down");

    private static Term only(final String query) {
        final List<Term> terms = JournalSearch.parse(query, SERVICES, LABELS);
        assertEquals(1, terms.size(), terms::toString);
        return terms.getFirst();
    }

    @Test
    void aPaymentReferenceIsReadInCapitals() {
        assertEquals(new Term(Kind.PAYMENT, "nt-0a1b2c", List.of("NT-0A1B2C")), only("nt-0a1b2c"));
    }

    @Test
    void aDiscordIdIsSeventeenToTwentyDigits() {
        assertEquals(Kind.DISCORD, only("123456789012345678").kind());
        assertEquals(Kind.NAME, only("1234").kind());
    }

    @Test
    void aPlayerUuidIsReadInLowerCase() {
        assertEquals(
                new Term(
                        Kind.UUID,
                        "0F8FAD5B-D9CB-469F-A165-70867728950E",
                        List.of("0f8fad5b-d9cb-469f-a165-70867728950e")),
                only("0F8FAD5B-D9CB-469F-A165-70867728950E"));
    }

    @Test
    void daysAreANumberWithD() {
        assertEquals(new Term(Kind.DAYS, "30d", List.of("30")), only("30d"));
    }

    @Test
    void aServiceIsOneTheJournalKnows() {
        assertEquals(new Term(Kind.SERVICE, "Steward-Agent", List.of("steward-agent")), only("Steward-Agent"));
    }

    @Test
    void aLevelWinsOverTheRunKindOfTheSameWord() {
        assertEquals(new Term(Kind.LEVEL, "down", List.of("DOWN")), only("down"));
        assertEquals(new Term(Kind.LEVEL, "warn", List.of("WARN")), only("warn"));
    }

    @Test
    void aRunKindIsReadWithHyphens() {
        assertEquals(new Term(Kind.RUN_KIND, "remove-plugin", List.of("REMOVE_PLUGIN")), only("remove-plugin"));
        assertEquals(Kind.RUN_KIND, only("backup").kind());
    }

    @Test
    void aRunStatusIsItsName() {
        assertEquals(new Term(Kind.RUN_STATUS, "failed", List.of("FAILED")), only("failed"));
    }

    @Test
    void aGaugeIsItsNameOrItsFirstWord() {
        assertEquals(new Term(Kind.GAUGE, "disk", List.of("DISK_PERCENT")), only("disk"));
        assertEquals(new Term(Kind.GAUGE, "exit-code", List.of("EXIT_CODE")), only("exit-code"));
    }

    @Test
    void anActionWordMatchesEveryLabelThatContainsIt() {
        assertEquals(new Term(Kind.ACTION, "granted", List.of("GRANT_ACCESS", "GRANT_ADMIN")), only("granted"));
        assertEquals(new Term(Kind.ACTION, "Access", List.of("GRANT_ACCESS", "REVOKE_ACCESS")), only("Access"));
    }

    @Test
    void anyOtherWordIsAName() {
        assertEquals(new Term(Kind.NAME, "Steve", List.of("Steve")), only("Steve"));
    }

    @Test
    void wordsBecomeOneTermEachAndABlankQueryNone() {
        assertEquals(
                List.of(Kind.NAME, Kind.DAYS),
                JournalSearch.parse("  Steve   30d ", SERVICES, LABELS).stream()
                        .map(Term::kind)
                        .toList());
        assertEquals(List.of(), JournalSearch.parse(" ", SERVICES, LABELS));
        assertEquals(List.of(), JournalSearch.parse(null, SERVICES, LABELS));
    }
}
