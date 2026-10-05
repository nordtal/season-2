package eu.nordtal.s2.dev;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * What {@code init} asks: the few things only a person knows, in the installer's own words.
 *
 * The prompts are {@code deploy/nordtal.sh}'s; locally Discord may be skipped and the address is asked.
 */
final class LocalQuestions {

    /** How an answer is read and what an empty one means. */
    enum Kind {
        /** Required, echoed. */
        PLAIN,
        /** May be skipped with Enter, echoed. */
        OPTIONAL_PLAIN,
        /** May be skipped with Enter, not echoed where that is possible. */
        OPTIONAL_SECRET,
        /** y/N, and only a yes writes anything. */
        LICENCE
    }

    /**
     * One question.
     *
     * @param name   the variable in {@code deploy/dev.env}
     * @param check  what an answer has to look like; anything non-empty passes when there is no rule
     */
    record Question(String name, Kind kind, String prompt, String hint, Predicate<String> check) {}

    private static final Pattern SNOWFLAKE = Pattern.compile("[0-9]{15,21}");

    private static final Pattern BROWSER_URL = Pattern.compile("https?://[A-Za-z0-9.-]+(:[0-9]+)?");

    private static final String SKIP = "Press Enter to skip: the stack still comes up, the bot simply does not run"
            + " and nobody can sign in to the interface.";

    static final List<Question> ALL = List.of(
            new Question(
                    "EULA",
                    Kind.LICENCE,
                    "Do you accept the Minecraft EULA? (https://aka.ms/MinecraftEULA)",
                    "Four Minecraft servers are about to start, and none of them may without this. [y/N]",
                    answer -> true),
            new Question(
                    "STEWARD_PUBLIC_URL",
                    Kind.PLAIN,
                    "What address will you open the interface on?",
                    "Scheme and host, no path - http://steward.localhost:8080 is the container itself and is the"
                            + " answer if you are not running the Vite dev server. Discord gets this plus"
                            + " /auth/callback as its redirect URI, and every security key is bound to its host.",
                    LocalQuestions::looksLikeBrowserUrl),
            new Question(
                    "NORDTAL_BOT_TOKEN",
                    Kind.OPTIONAL_SECRET,
                    "The Discord bot token.",
                    "A TEST application's token, never the production bot's - it would join the real guild from"
                            + " your laptop. " + SKIP,
                    answer -> true),
            new Question(
                    "STEWARD_DISCORD_CLIENT_ID",
                    Kind.OPTIONAL_PLAIN,
                    "The Discord application's Client ID - this is what the interface signs you in with.",
                    "The same test application, OAuth2 page. Its redirect URI has to be <the address above>"
                            + "/auth/callback. " + SKIP,
                    LocalQuestions::looksLikeSnowflake),
            new Question(
                    "STEWARD_DISCORD_CLIENT_SECRET",
                    Kind.OPTIONAL_SECRET,
                    "The same application's Client Secret.",
                    "OAuth2 -> Reset Secret, on the test application. " + SKIP,
                    answer -> true),
            new Question(
                    "NORDTAL_ACCESS_GUILD_ID",
                    Kind.OPTIONAL_PLAIN,
                    "The id of the guild this deployment belongs to.",
                    "A test guild, not the real one. " + SKIP,
                    LocalQuestions::looksLikeSnowflake));

    private LocalQuestions() {}

    /** @return whether {@code value} is a Discord id: digits only, 15 to 21 of them */
    static boolean looksLikeSnowflake(final String value) {
        return SNOWFLAKE.matcher(value).matches();
    }

    /** @return whether {@code value} is a scheme, a host and an optional port, and nothing after them */
    static boolean looksLikeBrowserUrl(final String value) {
        return BROWSER_URL.matcher(value).matches();
    }

    /** @return the host of such a URL: no scheme, no port, as the WebAuthn relying party id needs */
    static String hostOf(final String url) {
        final String rest = url.substring(url.indexOf("://") + 3);
        final int colon = rest.indexOf(':');
        return colon < 0 ? rest : rest.substring(0, colon);
    }

    /** @return whether {@code answer} is a yes to a licence question */
    static boolean isYes(final String answer) {
        return switch (answer.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "y", "yes", "true" -> true;
            default -> false;
        };
    }
}
