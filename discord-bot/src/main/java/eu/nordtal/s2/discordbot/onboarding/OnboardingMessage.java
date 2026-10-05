package eu.nordtal.s2.discordbot.onboarding;

import static eu.nordtal.s2.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.s2.discordbot.Card;
import eu.nordtal.s2.discordbot.DiscordRenderer;
import eu.nordtal.s2.discordbot.Ids;
import eu.nordtal.s2.discordbot.ManagedMessage;
import eu.nordtal.s2.discordbot.config.Languages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;

/**
 * The one message in the onboarding channel: an embed and a button per language, each opening the choice in it.
 *
 * A {@link ManagedMessage}, so a start or a changed channel edits the last one rather than posting another.
 */
final class OnboardingMessage {

    /** Its {@code managed_message.kind}. */
    static final String KIND = "ONBOARDING";

    /** Discord shows at most ten embeds on one message. */
    private static final int EMBEDS = 10;

    /** And at most five buttons in one row. */
    private static final int ROW = 5;

    private final Languages languages;
    private final DiscordRenderer messages;
    private final ManagedMessage managed;

    OnboardingMessage(final Languages languages, final DiscordRenderer messages, final ManagedMessage managed) {
        this.languages = languages;
        this.messages = messages;
        this.managed = managed;
    }

    /** Posts or edits the message in {@code channelId}; an empty id means none. */
    void publish(final String channelId) {
        final List<Languages.Language> shown =
                languages.all().stream().limit(EMBEDS).toList();
        final List<MessageEmbed> embeds = new ArrayList<>();
        final List<Button> buttons = new ArrayList<>();
        for (final Languages.Language language : shown) {
            final Locale locale = language.locale();
            final String button = messages.format(locale, MESSAGES.onboarding().button());
            embeds.add(Card.of(messages.format(locale, MESSAGES.onboarding().title()))
                    .field(
                            messages.format(locale, MESSAGES.onboarding().chooseHeading()),
                            messages.format(locale, MESSAGES.onboarding().choose(button)))
                    .field(
                            messages.format(locale, MESSAGES.onboarding().changeHeading()),
                            messages.format(locale, MESSAGES.onboarding().change()))
                    .build());
            buttons.add(Button.primary(Ids.ONBOARD + language.tag(), button));
        }
        final List<ActionRow> rows = new ArrayList<>();
        for (int first = 0; first < buttons.size(); first += ROW) {
            rows.add(ActionRow.of(buttons.subList(first, Math.min(first + ROW, buttons.size()))));
        }
        managed.publish(KIND, channelId, new ManagedMessage.Content(embeds, rows, null));
    }
}
