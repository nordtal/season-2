package eu.nordtal.s2.discordbot.access.payment;

import eu.nordtal.s2.discordbot.access.bunq.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settlement rule, in memory.
 * <p>
 * This is the one piece of money logic that is neither in SQL nor at bunq, and it decides what
 * somebody gets for an amount they were able to edit before paying. Every case below is one a
 * support ticket could be about.
 * </p>
 * <p>
 * The rule is asymmetric on purpose: <b>an order that the money covers is honoured exactly</b>,
 * and only a payment that falls short is re-derived from the amount. The earlier version derived
 * both directions from the amount, so paying the asked-for 10 € on a 60-days-plus-donation order
 * bought 90 days and no donor role. {@link #coveredOrderIsHonouredNotReDerived()} is that case.
 * </p>
 */
class TiersTest {

    /** The agreed product: 30/60/90 days at 3/5/7 EUR, with a 5 EUR donation surcharge. */
    private final Tiers tiers = Tiers.of(
            List.of(new Tier(30, 300), new Tier(60, 500), new Tier(90, 700)), 500);

    /** 60 days at 5 EUR with the donation added: the order that used to be mis-settled. */
    private static final Tiers.Order SIXTY_WITH_DONATION = new Tiers.Order(60, 500, true);

    /** 60 days at 5 EUR, no donation. */
    private static final Tiers.Order SIXTY_PLAIN = new Tiers.Order(60, 500, false);

    @Test
    @DisplayName("the price list is offered cheapest first")
    void orderedCheapestFirst() {
        assertEquals(List.of(30, 60, 90), tiers.all().stream().map(Tier::days).toList());
    }

    // ---------------------------------------------------------------- the order wins

    @Test
    @DisplayName("an order the money exactly covers is granted exactly")
    void exactOrderCovered() {
        final Tiers.Settlement settlement = tiers.resolve(1000, SIXTY_WITH_DONATION).orElseThrow();

        assertAll(
                () -> assertEquals(60, settlement.days()),
                () -> assertTrue(settlement.donation()),
                () -> assertEquals(500, settlement.donationCents()),
                () -> assertFalse(settlement.downgraded())
        );
    }

    @Test
    @DisplayName("paying the asked-for amount buys what was asked for, not the highest tier it covers")
    void coveredOrderIsHonouredNotReDerived() {
        // 10 EUR covers the 7 EUR tier, and the amount-only rule would have granted 90 days and no
        // donor role. The order says 60 days plus a donation, and the order wins.
        final Tiers.Settlement settlement = tiers.resolve(1000, SIXTY_WITH_DONATION).orElseThrow();
        final Tiers.Settlement amountOnly = tiers.resolve(1000).orElseThrow();

        assertAll(
                () -> assertEquals(60, settlement.days()),
                () -> assertTrue(settlement.donation()),
                () -> assertEquals(90, amountOnly.days(), "the amount alone would say something else"),
                () -> assertFalse(amountOnly.donation())
        );
    }

    @Test
    @DisplayName("surplus below the surcharge is ignored - no extra days, no partial credit")
    void surplusBelowTheSurcharge() {
        // Ordered 60 days at 5 EUR, paid 8. The 3 EUR left over is neither a donation nor enough
        // to move a tier, and days are bought in tiers.
        final Tiers.Settlement settlement = tiers.resolve(800, SIXTY_PLAIN).orElseThrow();

        assertAll(
                () -> assertEquals(60, settlement.days()),
                () -> assertFalse(settlement.donation()),
                () -> assertEquals(0, settlement.donationCents()),
                () -> assertFalse(settlement.downgraded())
        );
    }

    @Test
    @DisplayName("surplus reaching the surcharge is a donation even though none was ordered")
    void surplusReachingTheSurcharge() {
        // Ordered 60 days at 5 EUR, paid 10 without ticking the donation box.
        final Tiers.Settlement settlement = tiers.resolve(1000, SIXTY_PLAIN).orElseThrow();

        assertAll(
                () -> assertEquals(60, settlement.days(), "still the ordered tier"),
                () -> assertTrue(settlement.donation()),
                () -> assertEquals(500, settlement.donationCents()),
                () -> assertFalse(settlement.downgraded())
        );
    }

    @Test
    @DisplayName("a large surplus is one donation, not extra days")
    void largeSurplus() {
        final Tiers.Settlement settlement = tiers.resolve(2000, SIXTY_PLAIN).orElseThrow();

        assertAll(
                () -> assertEquals(60, settlement.days()),
                () -> assertEquals(1500, settlement.donationCents(),
                        "everything above the price of the days is the donation, and that is what "
                                + "the public thank-you names")
        );
    }

    // ---------------------------------------------------------------- a shortfall is downgraded

    @Test
    @DisplayName("a payment short of the order falls back to the tier it does cover")
    void shortPaymentDowngrades() {
        // Ordered 90 days at 7 EUR, edited the amount down to 4 EUR on the bunq.me page.
        final Tiers.Settlement settlement = tiers.resolve(400, new Tiers.Order(90, 700, false)).orElseThrow();

        assertAll(
                () -> assertEquals(30, settlement.days()),
                () -> assertFalse(settlement.donation()),
                () -> assertTrue(settlement.downgraded(), "the confirmation DM has to say so")
        );
    }

    @Test
    @DisplayName("dropping the donation is a shortfall too, even when the days still fit")
    void shortOfTheDonationIsADowngrade() {
        // Ordered 60 days plus the donation (10 EUR), paid 5. The days survive; the role does not.
        final Tiers.Settlement settlement = tiers.resolve(500, SIXTY_WITH_DONATION).orElseThrow();

        assertAll(
                () -> assertEquals(60, settlement.days()),
                () -> assertFalse(settlement.donation()),
                () -> assertTrue(settlement.downgraded())
        );
    }

    @Test
    @DisplayName("below the cheapest tier nothing is granted, order or no order")
    void belowTheCheapest() {
        assertAll(
                () -> assertEquals(Optional.empty(), tiers.resolve(299, SIXTY_PLAIN)),
                () -> assertEquals(Optional.empty(), tiers.resolve(299))
        );
    }

    // ---------------------------------------------------------------- no order behind it

    @Test
    @DisplayName("with no order the amount alone decides, highest tier first")
    void noOrderFallsBackToTheAmount() {
        // Kept for a payment with no request to honour. Nothing in the bot reaches this today:
        // the fallback matcher raises an unknown reference to the admin channel rather than
        // booking it, so every settlement the poll loop performs has an order.
        assertAll(
                () -> assertEquals(90, tiers.resolve(1000).orElseThrow().days()),
                () -> assertFalse(tiers.resolve(1000).orElseThrow().donation()),
                () -> assertEquals(90, tiers.resolve(1200).orElseThrow().days()),
                () -> assertTrue(tiers.resolve(1200).orElseThrow().donation(),
                        "7 EUR of tier plus 5 EUR left over")
        );
    }

    @Test
    @DisplayName("an order priced from a retired tier is still honoured")
    void orderSurvivesAPriceChange() {
        // The request stored 45 days at 4 EUR; the price list no longer has a 45-day tier. The
        // order is read off the row, not looked up, so it still settles.
        final Tiers.Settlement settlement = tiers.resolve(400, new Tiers.Order(45, 400, false)).orElseThrow();

        assertAll(
                () -> assertEquals(45, settlement.days()),
                () -> assertFalse(settlement.downgraded())
        );
    }

    // ---------------------------------------------------------------- money

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
