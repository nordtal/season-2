package eu.nordtal.season.dev;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The questions of {@code init}: the values that only a person knows, in the words of the installer.
 * The prompts are those of {@code deploy/nordtal.sh}.
 * Locally, the user can skip Discord, and {@code init} asks for the address.
 */
final class LocalQuestions {

    enum Kind {
        PLAIN,
        OPTIONAL_PLAIN,
        OPTIONAL_SECRET,
        LICENCE
    }

    /**
     * One question.
     *
     * @param name   the name that the installer uses, which is the variable in {@code deploy/dev.env}
     *               unless {@code home} is set
     * @param check  the shape of a valid answer. Without a rule, any non-empty answer passes
     * @param home   the file of the one service that reads the secret, as in {@code deploy/nordtal.sh}.
     *               The answer goes there instead
     */
    record Question(
            String name,
            Kind kind,
            String prompt,
            String hint,
            Predicate<String> check,
            @Nullable Home home) {

        Question(
                final String name,
                final Kind kind,
                final String prompt,
                final String hint,
                final Predicate<String> check) {
            this(name, kind, prompt, hint, check, null);
        }
    }

    record Home(String service, String name) {}

    private static final Pattern SNOWFLAKE = Pattern.compile("[0-9]{15,21}");

    private static final Pattern BROWSER_URL = Pattern.compile("https?://[A-Za-z0-9.-]+(:[0-9]+)?");

    private static final String SKIP =
            "Press Enter to skip. Discord features and Steward sign-in require the Discord settings.";

    static final List<Question> ALL = List.of(
            new Question(
                    "EULA",
                    Kind.LICENCE,
                    "Do you accept the Minecraft EULA? (https://aka.ms/MinecraftEULA)",
                    "[y/N]",
                    answer -> true),
            new Question(
                    "STEWARD_PUBLIC_URL",
                    Kind.PLAIN,
                    "What URL will you use to open Steward?",
                    "Use http://steward.localhost:8080 for the container or http://localhost:5173 for Vite."
                            + " Set the Discord redirect URI to this URL followed by /auth/callback.",
                    LocalQuestions::looksLikeBrowserUrl),
            new Question(
                    "NORDTAL_BOT_TOKEN",
                    Kind.OPTIONAL_SECRET,
                    "Enter the Discord bot token.",
                    "Use a test application. A production token connects this local bot to the real Discord server. "
                            + SKIP,
                    answer -> true,
                    new Home("discord-bot", "NORDTAL_BOT_TOKEN")),
            new Question(
                    "STEWARD_DISCORD_CLIENT_ID",
                    Kind.OPTIONAL_PLAIN,
                    "Enter the Discord application Client ID for Steward sign-in.",
                    "Open OAuth2 in the same test application. Set the redirect URI to <the URL above>"
                            + "/auth/callback. " + SKIP,
                    LocalQuestions::looksLikeSnowflake),
            new Question(
                    "STEWARD_DISCORD_CLIENT_SECRET",
                    Kind.OPTIONAL_SECRET,
                    "Enter that Discord application’s Client Secret.",
                    "Open OAuth2, then Reset Secret in the test application. " + SKIP,
                    answer -> true,
                    new Home("steward", "NORDTAL_STEWARD_WEB_DISCORD_CLIENT_SECRET")),
            new Question(
                    "NORDTAL_ACCESS_GUILD_ID",
                    Kind.OPTIONAL_PLAIN,
                    "Enter the Discord server ID.",
                    "Use a test server. " + SKIP,
                    LocalQuestions::looksLikeSnowflake),
            new Question(
                    "STEWARD_ROOT_DISCORD_ID",
                    Kind.OPTIONAL_PLAIN,
                    "Enter the Discord user ID for the first root admin.",
                    "Enable Developer Mode in Discord. Right-click your name and select Copy User ID."
                            + " This account can claim root access when no admins exist. Press Enter to skip root setup.",
                    LocalQuestions::looksLikeSnowflake));

    private LocalQuestions() {}

    static boolean looksLikeSnowflake(final String value) {
        return SNOWFLAKE.matcher(value).matches();
    }

    static boolean looksLikeBrowserUrl(final String value) {
        return BROWSER_URL.matcher(value).matches();
    }

    static String hostOf(final String url) {
        final String rest = url.substring(url.indexOf("://") + 3);
        final int colon = rest.indexOf(':');
        return colon < 0 ? rest : rest.substring(0, colon);
    }

    static boolean isYes(final String answer) {
        return switch (answer.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "y", "yes", "true" -> true;
            default -> false;
        };
    }
}
