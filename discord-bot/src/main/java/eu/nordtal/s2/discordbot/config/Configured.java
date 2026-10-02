package eu.nordtal.s2.discordbot.config;

import eu.nordtal.s2.database.payment.PaymentGateway;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Reports once at startup which optional Discord ids are empty, and so which features are off.
 *
 * Only the guild and the admin role are required; every other empty id silently switches its feature off.
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
     * @param gateway what steward last found about steward-bunq's credentials, so this one line stays complete
     */
    public static void report(final AccessSpec config, final PaymentGateway.State gateway) {
        final List<String> off = new ArrayList<>();
        addGateway(off, gateway);
        addRolesAndChannels(off, config);
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

    private static void addRolesAndChannels(final List<String> off, final AccessSpec config) {
        if (!isSet(config.roles().access())) {
            off.add("roles.access - nobody is given or taken the access role, and the reconcile "
                    + "does nothing (the grants in the database are still correct)");
        }
        if (!isSet(config.roles().donor())) {
            off.add("roles.donor - a donor is recorded in the database but gets no role");
        }
        if (!isSet(config.roles().adminPing())) {
            off.add("roles.admin-ping - admin alerts are posted without a mention");
        }
        if (!isSet(config.channels().admin())) {
            off.add("channels.admin - nothing is posted to an admin channel at all; alerts are "
                    + "logged here instead");
        }
        if (config.tiers().isEmpty()) {
            off.add("tiers - there is nothing to buy, so the contribution message offers only the " + "donation");
        }
    }

    private static void addLanguages(final List<String> off, final AccessSpec config) {
        for (final AccessSpec.LanguageSpec language : config.languages()) {
            final String path = "languages[" + language.tag() + "]";
            if (!isSet(language.role())) {
                off.add(path + ".role - no member is ever recorded as speaking " + language.tag());
            }
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
