package eu.nordtal.s2.discordbot.config;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Which Discord ids were actually filled in, and what the bot therefore does not do.
 *
 * <h2>Why this exists</h2>
 * Until 2026-09-14 every id in {@code access.yml} was mandatory: eleven snowflakes had to be in the
 * environment file before the bot would start at all, and a deployment that had a guild but not yet
 * a donor role could not be brought up to find out what else was wrong. That is backwards. The two
 * ids the bot genuinely cannot work without are the <b>guild</b> and the <b>admin role</b> - one
 * says where it lives, the other says who is allowed to administer it, and without the second
 * nobody can log in to Steward to fill the rest in. Everything else is a feature: leave the id
 * empty and the bot simply does not serve that feature.
 *
 * <h2>Why it is said out loud</h2>
 * A feature that is silently off is indistinguishable from one that is broken. Every consumer below
 * degrades quietly at the call site - a donation with no contribution channel is not an error, it
 * is a deployment that has not picked one yet - so the one place that must not be quiet is the
 * start. {@link #report(AccessSpec)} writes a single line naming what is unconfigured, so the
 * answer to "why did nothing appear in the channel" is in the log the operator already has.
 *
 * @see Configs#access()
 */
@Slf4j
public final class Configured {

    private Configured() {
    }

    /**
     * Whether an id was configured at all.
     *
     * <p>This is not the same question as "is it a valid snowflake" - {@link Configs} has already
     * refused anything that is neither empty nor digits. It is the check every JDA call needs in
     * front of it, because {@code getRoleById("")} and {@code getChannelById(_, "")} do not answer
     * {@code null}: they throw, and an unconfigured channel would come out as a stack trace rather
     * than as a feature nobody switched on.</p>
     *
     * @param id an id out of the configuration
     * @return whether there is something to look up
     */
    public static boolean isSet(final String id) {
        return id != null && !id.isBlank();
    }

    /**
     * Says once, at startup, which features have no id behind them.
     *
     * @param config   the loaded and validated access configuration
     * @param payments whether bunq is configured; it lives in {@code bot.yml} rather than here, and
     *                 is passed in so that this one line is the whole answer to "what is switched
     *                 off" rather than one of two places to look
     */
    public static void report(final AccessSpec config, final boolean payments) {
        final List<String> off = new ArrayList<>();

        if (!payments) {
            off.add("bunq.api-key / bunq.account-id in bot.yml - nothing is ever polled for and "
                    + "nothing can be bought; the rest of the bot is unaffected");
        }

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
            off.add("tiers - there is nothing to buy, so the contribution message offers only the "
                    + "donation");
        }

        for (final AccessSpec.LanguageSpec language : config.languages()) {
            final String path = "languages[" + language.tag() + "]";
            if (!isSet(language.role())) {
                off.add(path + ".role - no member is ever recorded as speaking " + language.tag());
            }
            if (!isSet(language.contributionChannel())) {
                off.add(path + ".contribution-channel - no contribution message and no public "
                        + "thank-you in " + language.tag());
            }
            if (!isSet(language.linkChannel())) {
                off.add(path + ".link-channel - no link message in " + language.tag());
            }
            if (!isSet(language.hungerGamesChannel())) {
                off.add(path + ".hunger-games-channel - no register message in " + language.tag());
            }
        }

        if (off.isEmpty()) {
            log.info("Every Discord id in access.yml is filled in; no feature is switched off.");
            return;
        }
        log.warn("{} setting(s) in access.yml are empty, so the features behind them are not "
                + "served. Fill them in in Steward, not in a file:\n  {}",
                off.size(), String.join("\n  ", off));
    }
}
