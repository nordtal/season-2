package eu.nordtal.season.discordbot.config;

import eu.nordtal.season.database.payment.PaymentGateway;
import eu.nordtal.season.database.payment.Tiers;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** Logs at startup which optional Discord ids are empty. An empty id switches its feature off. */
@Slf4j
public final class Configured {

    private Configured() {}

    /** Returns whether the id is neither null nor blank. A JDA lookup throws on an empty id. */
    public static boolean isSet(final String id) {
        return id != null && !id.isBlank();
    }

    public static void report(final AccessSpec config, final Tiers tiers, final PaymentGateway.State gateway) {
        final List<String> off = new ArrayList<>();
        addGateway(off, gateway);
        addChannels(off, config);
        if (tiers.all().isEmpty()) {
            off.add("prices.tiers");
        }
        addLanguages(off, config);

        if (off.isEmpty()) {
            log.info("access settings complete");
            return;
        }
        log.warn("access settings empty, features off: count={} keys={}", off.size(), String.join(",", off));
    }

    private static void addGateway(final List<String> off, final PaymentGateway.State gateway) {
        switch (gateway) {
            case OFF -> off.add("bunq:off");
            case UNKNOWN -> off.add("bunq:unknown");
            case ON -> {}
        }
    }

    private static void addChannels(final List<String> off, final AccessSpec config) {
        if (!isSet(config.channels().admin())) {
            off.add("channels.admin");
        }
    }

    private static void addLanguages(final List<String> off, final AccessSpec config) {
        for (final AccessSpec.LanguageSpec language : config.languages()) {
            final String path = "languages[" + language.tag() + "]";
            if (!isSet(language.contributionChannel())) {
                off.add(path + ".contribution-channel");
            }
            if (!isSet(language.linkChannel())) {
                off.add(path + ".link-channel");
            }
            if (!isSet(language.hungerGamesChannel())) {
                off.add(path + ".hunger-games-channel");
            }
        }
    }
}
