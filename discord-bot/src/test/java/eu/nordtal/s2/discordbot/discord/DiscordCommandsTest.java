package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.Catalogue;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.commands.remote.RequestArguments;
import eu.nordtal.s2.common.message.Messages;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That every declared command can actually become a Discord slash command, and that a confirmation
 * button says what it will do.
 *
 * <h2>Why the limits are checked here and not discovered on the guild</h2>
 * JDA validates the command set when it is registered and refuses <b>all of it</b> if one entry is
 * wrong - a name with a capital letter, a description over a hundred characters, a fourth level of
 * nesting. The symptom is not "that command is missing", it is that the guild has <em>no</em>
 * commands and the bot logs one exception at startup that nobody is watching for. Every limit below
 * is read from the real bundle and the real catalogue, so adding a command with a long description
 * fails the build rather than the deployment.
 */
class DiscordCommandsTest {

    private static final Messages MESSAGES = Messages.load(
            DiscordCommandsTest.class.getClassLoader(), "messages/commands",
            Locale.ENGLISH, Locale.GERMAN);

    /** Discord's own: lowercase letters, digits, hyphens and underscores, at most 32. */
    private static final Pattern NAME = Pattern.compile("^[-_a-z0-9]{1,32}$");

    private static final int MAX_DESCRIPTION = 100;
    private static final int MAX_COMPONENT_ID = 100;

    private static List<Declaration> inDiscord() {
        return Catalogue.all().stream()
                .filter(declaration -> declaration.surfaces().contains(Surface.DISCORD))
                .toList();
    }

    @Test
    @DisplayName("no command is deeper than Discord's three levels")
    void threeLevels() {
        // /root group sub, and no more. The paths were chosen to be typed the same way on both
        // surfaces, so this is the tighter of the two limits and the one that decides the shape.
        final List<String> tooDeep = inDiscord().stream()
                .filter(declaration -> declaration.path().size() > 3)
                .map(Declaration::name)
                .toList();
        assertEquals(List.of(), tooDeep);
    }

    @Test
    @DisplayName("every path segment and argument name is a legal Discord name")
    void names() {
        final List<String> illegal = new ArrayList<>();
        for (final Declaration declaration : inDiscord()) {
            declaration.path().stream()
                    .filter(segment -> !NAME.matcher(segment).matches())
                    .forEach(segment -> illegal.add(declaration.name() + ": '" + segment + "'"));
            declaration.arguments().stream()
                    .map(Argument::name)
                    .filter(name -> !NAME.matcher(name).matches())
                    .forEach(name -> illegal.add(declaration.name() + ": option '" + name + "'"));
        }
        assertEquals(List.of(), illegal,
                "Discord names are lowercase, at most 32 characters, letters digits - and _ only");
    }

    @Test
    @DisplayName("every description fits, in both languages, after shortening")
    void descriptions() {
        final List<String> tooLong = new ArrayList<>();

        for (final Declaration declaration : inDiscord()) {
            check(declaration.describeKey(), tooLong);
            declaration.arguments().forEach(argument ->
                    check("command.argument." + argument.name(), tooLong));
            check("command.describe.root." + declaration.path().getFirst(), tooLong);
        }
        assertEquals(List.of(), tooLong,
                "a description is over Discord's limit even after being cut at its first sentence."
                        + " Rewrite the FIRST sentence of the describe key - the rest can stay long,"
                        + " it is what chat shows.");
    }

    private void check(final String key, final List<String> tooLong) {
        for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            final String text = DiscordCommands.shorten(MESSAGES.get(locale, key));
            if (text.length() > MAX_DESCRIPTION || text.isBlank()) {
                tooLong.add(key + " (" + locale.getLanguage() + ", " + text.length() + " chars)");
            }
        }
    }

    @Test
    @DisplayName("shortening cuts at the first full stop, and only hard-cuts when there is none")
    void shortening() {
        assertEquals("Short enough.", DiscordCommands.shorten("Short enough."));

        final String twoSentences = "Deletes the farm world and regenerates it. "
                + "Everything standing in it is gone, and so is everything anybody left there,"
                + " which is a great deal of somebody's afternoon.";
        assertEquals("Deletes the farm world and regenerates it.",
                DiscordCommands.shorten(twoSentences));

        final String oneLongSentence = "x".repeat(150);
        assertEquals(MAX_DESCRIPTION, DiscordCommands.shorten(oneLongSentence).length());
        assertTrue(DiscordCommands.shorten(oneLongSentence).endsWith("…"));
    }

    // ------------------------------------------------------------------ the confirmation button

    @Test
    @DisplayName("a confirmation button round-trips its command and its arguments")
    void theButtonCarriesWhatItWillDo() {
        // Nothing is held in memory, which is what lets a button survive a restart of the bot - and
        // what makes one from an older build identifiable rather than merely inert.
        for (final Declaration declaration : inDiscord()) {
            if (!declaration.irreversible()) {
                continue;
            }
            final String arguments = RequestArguments.encode(declaration, sample(declaration));
            final String id = DiscordCommands.buttonId(declaration, arguments);

            assertEquals(Optional.of(String.join(" ", declaration.path())),
                    DiscordCommands.commandOf(id), declaration.name());
            assertEquals(arguments, DiscordCommands.argumentsOf(id), declaration.name());
            assertTrue(id.length() <= MAX_COMPONENT_ID,
                    declaration.name() + "'s button id is " + id.length() + " characters and"
                            + " Discord allows " + MAX_COMPONENT_ID);
        }
    }

    @Test
    @DisplayName("a button from another flow is not read as a command")
    void otherFlowsAreLeftAlone() {
        // The bot has buttons for buying access, linking an account and registering for the hunger
        // games. One of them being read as "yes, switch the phase" is the failure this prefix
        // exists to make impossible.
        assertEquals(Optional.empty(), DiscordCommands.commandOf(Ids.BUY));
        assertEquals(Optional.empty(), DiscordCommands.commandOf(Ids.CONFIRM));
        assertEquals(Optional.empty(), DiscordCommands.commandOf(Ids.LINK));
    }

    @Test
    @DisplayName("an id with no arguments part is refused rather than read as a command with none")
    void aMalformedIdIsNotACommand() {
        assertEquals(Optional.empty(),
                DiscordCommands.commandOf(DiscordCommands.BUTTON + "phase set"));
        // ...and one that does carry the separator, even with nothing after it, is.
        assertEquals(Optional.of("phase set"),
                DiscordCommands.commandOf(DiscordCommands.BUTTON + "phase set|"));
    }

    private static Values sample(final Declaration declaration) {
        final Map<String, Object> values = new LinkedHashMap<>();
        for (final Argument argument : declaration.arguments()) {
            values.put(argument.name(), switch (argument.kind()) {
                case WORD -> "ancient-debris";
                case GREEDY_STRING -> "2026-10-01 18:00";
                case INTEGER -> argument.max();
                case PLAYER -> UUID.fromString("11111111-2222-3333-4444-555555555555");
                case ACCOUNT -> "100000000000000009";
                case CHOICE -> argument.choices().getLast();
            });
        }
        return new Values(declaration, values);
    }
}
