package eu.nordtal.s2.accessbot.payment;

import eu.nordtal.s2.accessbot.bunq.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "pay what you get" rule, in memory.
 * <p>
 * This is the one piece of money logic that is neither in SQL nor at bunq, and it decides what
 * somebody gets for an amount they were able to edit before paying. Every case below is one a
 * support ticket could be about.
 * </p>
 */
class TiersTest {

    /** The agreed product: 30/60/90 days at 3/5/7 EUR, with a 5 EUR donation surcharge. */
    private final Tiers tiers = Tiers.of(
            List.of(new Tier(30, 300), new Tier(60, 500), new Tier(90, 700)), 500);

    @Test
    @DisplayName("the price list is offered cheapest first")
    void orderedCheapestFirst() {
        assertEquals(List.of(30, 60, 90), tiers.all().stream().map(Tier::days).toList());
    }

    @Test
    @DisplayName("paying exactly a tier price buys that tier and no donation")
    void exactPrice() {
        final Tiers.Settlement settlement = tiers.resolve(500).orElseThrow();
        assertAll(
                () -> assertEquals(60, settlement.tier().days()),
                () -> assertFalse(settlement.donation())
        );
    }

    @Test
    @DisplayName("paying a tier price plus the surcharge buys that tier and the donation")
    void tierPlusDonation() {
        final Tiers.Settlement settlement = tiers.resolve(1200).orElseThrow();
        assertAll(
                () -> assertEquals(90, settlement.tier().days()),
                () -> assertTrue(settlement.donation())
        );
    }

    @Test
    @DisplayName("paying less than was ordered downgrades to the tier the amount covers")
    void downgrade() {
        // Ordered 90 days at 7 EUR, edited the amount down to 4 EUR on the bunq.me page.
        final Tiers.Settlement settlement = tiers.resolve(400).orElseThrow();
        assertAll(
                () -> assertEquals(30, settlement.tier().days()),
                () -> assertFalse(settlement.donation())
        );
    }

    @Test
    @DisplayName("below the cheapest tier nothing is granted")
    void belowTheCheapest() {
        assertEquals(Optional.empty(), tiers.resolve(299));
    }

    @Test
    @DisplayName("the rule maximises days, so 10 EUR is 90 days without a donor role")
    void daysBeatDonation() {
        // The literal reading of the agreed rule: highest tier the amount covers first, donation
        // only out of what is left. Somebody paying 5 + 5 gets 90 days and no donor role rather
        // than 60 days and the role. Written down because it is the case that will be asked about.
        final Tiers.Settlement settlement = tiers.resolve(1000).orElseThrow();
        assertAll(
                () -> assertEquals(90, settlement.tier().days()),
                () -> assertFalse(settlement.donation())
        );
    }

    @Test
    @DisplayName("money round-trips through bunq's decimal strings exactly")
    void moneyRoundTrip() {
        assertAll(
                () -> assertEquals("3.00", Money.toDecimalString(300)),
                () -> assertEquals("12.05", Money.toDecimalString(1205)),
                () -> assertEquals(300, Money.toCents("3.00")),
                () -> assertEquals(500, Money.toCents("5")),
                // Season 1 parsed amounts as float and compared them with <, which is how an exact
                // 5.00 could fail an "at least 5" check.
                () -> assertEquals(1205, Money.toCents("12.05"))
        );
    }
}
