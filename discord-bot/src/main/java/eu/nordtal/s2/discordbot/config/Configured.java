package eu.nordtal.s2.discordbot.config;

import eu.nordtal.s2.common.payment.PaymentGateway;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Which Discord ids were actually filled in, and what the bot therefore does not do.
 *
 * Why this exists: a deployment that has a guild but not yet a donor role must still be able to start and say what
 * else is missing. The two ids the bot genuinely cannot work without are the guild and the admin role - one says
 * where it lives, the other says who is allowed to administer it, and without the second nobody can log in to
 * Steward to fill the rest in. Everything else is a feature: leave the id empty and the bot simply does not serve
 * that feature.
 *
 * Why it is said out loud: A feature that is silently off is indistinguishable from one that is broken. Every
 * consumer below degrades quietly at the call site - a donation with no contribution channel is not an error, it is
 * a deployment that has not picked one yet - so the one place that must not be quiet is the start.
 * {@link #report(AccessSpec, PaymentGateway.State)} writes a single line naming what is unconfigured, so the answer
 * to "why did nothing appear in the channel" is in the log the operator already has.
 *
 * @see Configs#access()
 */
@Slf4j
public final class Configured {

    private Configured() {}

    /**
     * Whether an id was configured at all.
     *
     * This is not the same question as "is it a valid snowflake" - {@link Configs} has already refused anything
     * that is neither empty nor digits. It is the check every JDA call needs in front of it, because
     * {@code getRoleById("")} and {@code getChannelById(_, "")} do not answer {@code null}: they throw, and an
     * unconfigured channel would come out as a stack trace rather than as a feature nobody switched on.
     *
     * @param id an id out of the configuration.
     * @return whether there is something to look up.
     */
    public static boolean isSet(final String id) {
        return id != null && !id.isBlank();
    }

    /**
     * Says once, at startup, which features have no id behind them.
     *
     * @param config  the loaded and validated access configuration
     * @param gateway what steward-worker last said about its bunq credentials. This bot has no
     *                bunq key of its own, so it cannot answer the question by looking at a file -
     *                it reads the answer the worker wrote. It is carried here rather than logged
     *                separately so that this one line stays the whole list of what is switched
     *                off, instead of being one of two places to look.
     */
    public static void report(final AccessSpec config, final PaymentGateway.State gateway) {
        final List<String> off = new ArrayList<>();
        addGateway(off, gateway);
        addRolesAndChannels(off, config);
        addLanguages(off, config);

        if (off.isEmpty()) {
            log.info("Every Discord id in access.yml is filled in; no feature is switched off.");
            return;
        }
        log.warn(
                "{} setting(s) in access.yml are empty, so the features behind them are not "
                        + "served. Fill them in in Steward, not in a file:\n  {}",
                off.size(),
                String.join("\n  ", off));
    }

    private static void addGateway(final List<String> off, final PaymentGateway.State gateway) {
        switch (gateway) {
            case OFF ->
                off.add("bunq in steward-worker's steward.yml - the worker started and "
                        + "found no key, so nothing is ever polled for and nothing can be bought; the "
                        + "rest of the bot is unaffected");
            // Not "off": nobody has said. A worker that never started and one that started without a key look alike.
            case UNKNOWN ->
                off.add("bunq - no steward-worker has said whether it has a key since "
                        + "this database was created. Read steward-worker's own start line: it says "
                        + "'bunq is ON' or 'bunq is OFF' in one sentence");
            case ON -> {
                // Nothing to report. A gateway that is on is not a feature that is switched off.
            }
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
