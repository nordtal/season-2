package eu.nordtal.s2.settings.network;

import eu.nordtal.jcore.config.spec.Specs;
import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What access costs: the bot offers it and steward books a payment by it. */
@ConfigSpec
public interface PricesSpec {

    @Order(1)
    @Name("Tiers")
    @Key("tiers")
    @Comment({
        "What can be bought: a number of days and its price in cents per entry.",
        "More days must cost more, and no two entries may offer the same days.",
        "A tier is identified by its day count, so changing 'days' retires it.",
        "",
        "The list may not be empty. If you have emptied it, this is the shape:",
        "",
        "  tiers:",
        "  - days: 30",
        "    price-cents: 300",
        "  - days: 60",
        "    price-cents: 500"
    })
    @Explain(
            "What can be bought. Entries must be ordered by days ascending with price rising to match; changing 'days' on an entry retires that tier.")
    default List<TierSpec> tiers() {
        return List.of(tier(30, 300), tier(60, 500), tier(90, 700));
    }

    @Order(2)
    @Name("Donation (cents)")
    @Key("donation-cents")
    @Comment({
        "The optional surcharge that grants the permanent donor role, in cents.",
        "",
        "Money above the ordered total is a donation once it reaches this amount."
    })
    @Explain(
            "The extra amount that grants the donor role; a surplus of at least this much above the order is a donation.")
    default int donationCents() {
        return 500;
    }

    /** One purchasable period: a number of days for a price. */
    @ConfigSpec
    interface TierSpec {

        @Order(1)
        @Name("Duration (days)")
        @Key("days")
        @Comment("How many days of access this buys. A day is exactly 24 hours.")
        @NoExplanationNeeded
        default int days() {
            return 30;
        }

        @Order(2)
        @Name("Price (cents)")
        @Key("price-cents")
        @Comment("What it costs, in cents. Integer cents everywhere; never a float.")
        @NoExplanationNeeded
        default int priceCents() {
            return 300;
        }
    }

    /** {@code createUnsafe} applies no defaults, so every {@code @Key} of {@link TierSpec} is listed. */
    private static TierSpec tier(final int days, final int priceCents) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("days", days);
        values.put("price-cents", priceCents);
        return Specs.createUnsafe(TierSpec.class, values);
    }
}
