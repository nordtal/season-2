package eu.nordtal.season.discordbot.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import eu.nordtal.season.spec.annotation.Secret;

/**
 * The {@code bot} group: the Discord token, validated at startup.
 *
 * It comes from {@code NORDTAL_BOT_TOKEN}; the bunq credentials belong to {@code steward}.
 */
@ConfigSpec
public interface BotSpec {

    @Order(1)
    @Name("Bot token")
    @Key("token")
    @Secret
    @NoExplanationNeeded
    default String token() {
        return "";
    }

    @Order(2)
    @Name("Steward's address")
    @Key("steward-url")
    @Explain("Where a run's card in the admin channel links to; empty shows no link.")
    default String stewardUrl() {
        return "";
    }
}
