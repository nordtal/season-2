package eu.nordtal.season.settings.network;

import eu.nordtal.season.spec.Specs;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What access costs: the bot offers it and steward books a payment by it. */
@ConfigSpec
public interface PricesSpec {

    @Order(1)
    @Name("Tiers")
    @Key("tiers")
    @Explain(
            "What can be bought. Entries must be ordered by days ascending with price rising to match; changing 'days' on an entry retires that tier.")
    default List<TierSpec> tiers() {
        return List.of(tier(30, 300), tier(60, 500), tier(90, 700));
    }

    @Order(2)
    @Name("Donation (cents)")
    @Key("donation-cents")
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
        @NoExplanationNeeded
        default int days() {
            return 30;
        }

        @Order(2)
        @Name("Price (cents)")
        @Key("price-cents")
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
