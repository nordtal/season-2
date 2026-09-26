package eu.nordtal.s2.discordbot.access.payment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.payment.Money;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The settlement rule, in memory.
 *
 * This is the one piece of money logic that is neither in SQL nor at bunq, and it decides what somebody gets for an
 * amount they were able to edit before paying. Every case below is one a support ticket could be about.
 *
 * The rule is asymmetric on purpose: an order that the money covers is honoured exactly, and only a payment that
 * falls short is re-derived from the amount. Deriving both directions from the amount would let the asked-for 10 €
 * on a 60-days-plus-donation order buy 90 days and no donor role instead.
 * {@link #anOrderTheMoneyExactlyCoversIsGrantedExactly()} is that case.
 */
class TiersTest {

    /** The agreed product: 30/60/90 days at 3/5/7 EUR, with a 5 EUR donation surcharge. */
    private final Tiers tiers = Tiers.of(List.of(new Tier(30, 300), new Tier(60, 500), new Tier(90, 700)), 500);

    /** 60 days at 5 EUR with the donation added: the order that used to be mis-settled. */
    private static final Tiers.Order SIXTY_WITH_DONATION = new Tiers.Order(60, 500, 500);

    /** 60 days at 5 EUR, no donation. */
    private static final Tiers.Order SIXTY_PLAIN = new Tiers.Order(60, 500, 0);

    @Test
    void thePriceListIsOfferedCheapestFirst() {
        assertEquals(List.of(30, 60, 90), tiers.all().stream().map(Tier::days).toList());
    }

    // The order wins.

    @Test
    void anOrderTheMoneyExactlyCoversIsGrantedExactly() {
        final Tiers.Settlement settlement =
                tiers.resolve(1000, SIXTY_WITH_DONATION).orElseThrow();

        assertAll(
                () -> assertEquals(60, settlement.days()),
                () -> assertTrue(settlement.donation()),
                () -> assertEquals(500, settlement.donationCents()),
                () -> assertFalse(settlement.downgraded()));
    }

    @Test
    void payingTheAskedForAmountBuysWhatWasAskedForNotTheHighestTierItCovers() {
        // 10 EUR covers the 7 EUR tier, but the order says 60 days plus a donation, and the order wins.
        final Tiers.Settlement settlement =
                tiers.resolve(1000, SIXTY_WITH_DONATION).orElseThrow();
        final Tiers.Settlement amountOnly = tiers.resolve(1000).orElseThrow();

        assertAll(
                () -> assertEquals(60, settlement.days()),
                () -> assertTrue(settlement.donation()),
                () -> assertEquals(90, amountOnly.days(), "the amount alone would say something else"),
                () -> assertFalse(amountOnly.donation()));
    }

    @Test
    void surplusBelowTheSurchargeIsIgnoredNoExtraDaysNoPartialCredit() {
        // Ordered 60 days at 5 EUR, paid 8; the 3 EUR left over is neither a donation nor a tier move.
        final Tiers.Settlement settlement = tiers.resolve(800, SIXTY_PLAIN).orElseThrow();

        assertAll(
                () -> assertEquals(60, settlement.days()),
                () -> assertFalse(settlement.donation()),
                () -> assertEquals(0, settlement.donationCents()),
                () -> assertFalse(settlement.downgraded()));
    }

    @Test
    void surplusReachingTheSurchargeIsADonationEvenThoughNoneWasOrdered() {
        // Ordered 60 days at 5 EUR, paid 10 without ticking the donation box.
        final Tiers.Settlement settlement = tiers.resolve(1000, SIXTY_PLAIN).orElseThrow();

        assertAll(
                () -> assertEquals(60, settlement.days(), "still the ordered tier"),
                () -> assertTrue(settlement.donation()),
                () -> assertEquals(500, settlement.donationCents()),
                () -> assertFalse(settlement.downgraded()));
    }

    @Test
    void aLargeSurplusIsOneDonationNotExtraDays() {
        final Tiers.Settlement settlement = tiers.resolve(2000, SIXTY_PLAIN).orElseThrow();

        assertAll(
                () -> assertEquals(60, settlement.days()),
                () -> assertEquals(
                        1500,
                        settlement.donationCents(),
                        "everything above the price of the days is the donation, and that is what "
                                + "the public thank-you names"));
    }

    // A shortfall is downgraded.

    @Test
    void aPaymentShortOfTheOrderFallsBackToTheTierItDoesCover() {
        // Ordered 90 days at 7 EUR, edited the amount down to 4 EUR on the bunq.me page.
        final Tiers.Settlement settlement =
                tiers.resolve(400, new Tiers.Order(90, 700, 0)).orElseThrow();

        assertAll(
                () -> assertEquals(30, settlement.days()),
                () -> assertFalse(settlement.donation()),
                () -> assertTrue(settlement.downgraded(), "the confirmation DM has to say so"));
    }

    @Test
    void droppingTheDonationIsAShortfallTooEvenWhenTheDaysStillFit() {
        // Ordered 60 days plus the donation (10 EUR), paid 5. The days survive; the role does not.
        final Tiers.Settlement settlement =
                tiers.resolve(500, SIXTY_WITH_DONATION).orElseThrow();

        assertAll(
                () -> assertEquals(60, settlement.days()),
                () -> assertFalse(settlement.donation()),
                () -> assertTrue(settlement.downgraded()));
    }

    @Test
    void belowTheCheapestTierNothingIsGrantedOrderOrNoOrder() {
        assertAll(
                () -> assertEquals(Optional.empty(), tiers.resolve(299, SIXTY_PLAIN)),
                () -> assertEquals(Optional.empty(), tiers.resolve(299)));
    }

    // No order behind it.

    @Test
    void withNoOrderTheAmountAloneDecidesHighestTierFirst() {
        // Kept for a payment with no request to honour: the fallback matcher raises an unknown reference instead.
        assertAll(
                () -> assertEquals(90, tiers.resolve(1000).orElseThrow().days()),
                () -> assertFalse(tiers.resolve(1000).orElseThrow().donation()),
                () -> assertEquals(90, tiers.resolve(1200).orElseThrow().days()),
                () -> assertTrue(tiers.resolve(1200).orElseThrow().donation(), "7 EUR of tier plus 5 EUR left over"));
    }

    @Test
    void m8AnOrderSurvivesAChangeToTheDonationSurchargeNotOnlyToATierPrice() {
        // Ordered 60 days at a 5 EUR surcharge and paid 10 EUR; the surcharge is lowered to 3 EUR before settling.
        final Tiers afterTheChange = Tiers.of(List.of(new Tier(30, 300), new Tier(60, 500), new Tier(90, 700)), 300);

        final Tiers.Settlement settlement =
                afterTheChange.resolve(1000, SIXTY_WITH_DONATION).orElseThrow();

        assertAll(
                () -> assertEquals(60, settlement.days()),
                () -> assertFalse(settlement.downgraded(), "the payer paid exactly what was asked"),
                () -> assertTrue(settlement.donation()),
                () -> assertEquals(
                        500,
                        settlement.donationCents(),
                        "the donation is what was ordered and paid, not what the surcharge costs" + " today"));
    }

    @Test
    void m8ARaisedSurchargeDoesNotTurnAFullyPaidOrderIntoADowngrade() {
        // The dangerous direction: the surcharge moves to 8 EUR after ordering, so 1000 reads as short.
        final Tiers afterTheChange = Tiers.of(List.of(new Tier(30, 300), new Tier(60, 500), new Tier(90, 700)), 800);

        final Tiers.Settlement settlement =
                afterTheChange.resolve(1000, SIXTY_WITH_DONATION).orElseThrow();

        assertAll(
                () -> assertEquals(60, settlement.days()),
                () -> assertFalse(
                        settlement.downgraded(),
                        "a payer who paid the asked-for amount was downgraded because the price"
                                + " list moved after they ordered"));
    }

    @Test
    void anOrderPricedFromARetiredTierIsStillHonoured() {
        // The price list no longer has a 45-day tier; the order is read off the row, not looked up.
        final Tiers.Settlement settlement =
                tiers.resolve(400, new Tiers.Order(45, 400, 0)).orElseThrow();

        assertAll(() -> assertEquals(45, settlement.days()), () -> assertFalse(settlement.downgraded()));
    }

    // Money.

    @Test
    void moneyRoundTripsThroughBunqsDecimalStringsExactly() {
        assertAll(
                () -> assertEquals("3.00", Money.toDecimalString(300)),
                () -> assertEquals("12.05", Money.toDecimalString(1205)),
                () -> assertEquals(300, Money.toCents("3.00")),
                () -> assertEquals(500, Money.toCents("5")),
                // A float comparison with < is how an exact 5.00 could fail an "at least 5" check.
                () -> assertEquals(1205, Money.toCents("12.05")));
    }
}
