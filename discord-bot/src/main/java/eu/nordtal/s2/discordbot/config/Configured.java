package eu.nordtal.s2.discordbot.config;

import eu.nordtal.s2.database.payment.PaymentGateway;
import eu.nordtal.s2.database.payment.Tiers;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Reports once at startup which optional Discord ids are empty, and so which features are off.
 *
 * Only the guild is required; every other empty id silently switches its feature off.
 */
@Slf4j
public final class Configured {

    private Configured() {}

    /**
     * Returns whether an id was configured, which every JDA lookup needs since an empty id throws.
     *
     * @param id an id out of the configuration.
     * @return whether there is something to look up.
     */
    public static boolean isSet(final String id) {
        return id != null && !id.isBlank();
    }

    /**
     * Logs one line naming every feature that has no id behind it.
     *
     * @param config the loaded and validated access configuration
     * @param tiers the network's price list, whose emptiness switches the purchase off
     * @param gateway what steward last found about steward-bunq's credentials, so this one line stays complete
     */
    public static void report(final AccessSpec config, final Tiers tiers, final PaymentGateway.State gateway) {
        final List<String> off = new ArrayList<>();
        addGateway(off, gateway);
        addChannels(off, config);
        if (tiers.all().isEmpty()) {
            off.add("prices.tiers - there is nothing to buy, so the contribution message offers only the donation");
        }
        addLanguages(off, config);

        if (off.isEmpty()) {
            log.info("Every Discord id in the access group is filled in; no feature is switched off.");
            return;
        }
        log.warn(
                "{} setting(s) in the access group are empty, so the features behind them are not "
                        + "served. Fill them in in Steward, not in a file:\n  {}",
                off.size(),
                String.join("\n  ", off));
    }

    private static void addGateway(final List<String> off, final PaymentGateway.State gateway) {
        switch (gateway) {
            case OFF ->
                off.add("bunq - steward-bunq holds no key, so nothing is ever polled for and"
                        + " nothing can be bought; the rest of the bot is unaffected");
            // Not "off": a steward that never started and one without a key look alike.
            case UNKNOWN ->
                off.add("bunq - no steward has said whether steward-bunq has a key since "
                        + "this database was created. Read steward-bunq's start line: it says "
                        + "'bunq is ON' or 'bunq is OFF' in one sentence");
            case ON -> {}
        }
    }

    private static void addChannels(final List<String> off, final AccessSpec config) {
        if (!isSet(config.channels().admin())) {
            off.add("channels.admin - nothing is posted to an admin channel at all; alerts are "
                    + "logged here instead");
        }
    }

    private static void addLanguages(final List<String> off, final AccessSpec config) {
        for (final AccessSpec.LanguageSpec language : config.languages()) {
            final String path = "languages[" + language.tag() + "]";
            if (!isSet(language.contributionChannel())) {
                off.add(path + ".contribution-channel - no contribution message and no public " + "thank-you in "
                        + language.tag());
            }
            if (!isSet(language.linkChannel())) {
                off.add(path + ".link-channel - no link message in " + language.tag());
            }
            if (!isSet(language.hungerGamesChannel())) {
                off.add(path + ".hunger-games-channel - no register message in " + language.tag());
            }
        }
    }
}
