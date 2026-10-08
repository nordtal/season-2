package eu.nordtal.season.discordbot.onboarding;

import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.discordbot.Card;
import eu.nordtal.season.discordbot.DiscordRenderer;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.ManagedMessage;
import eu.nordtal.season.discordbot.Mark;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;

/**
 * The two onboarding messages: the welcome in the onboarding channel and the message to change later in another.
 *
 * Each is a {@link ManagedMessage}, so a start or a changed channel edits the last one rather than posting another.
 */
final class OnboardingMessage {

    /** The welcome's {@code managed_message.kind}. */
    static final String KIND = "ONBOARDING";

    /** The change message's {@code managed_message.kind}. */
    static final String CHANGE_KIND = "ONBOARDING_CHANGE";

    /** The welcome's banner, from {@code banners/}. */
    static final String BANNER = "onboarding.png";

    /** Discord shows at most ten embeds on one message. */
    private static final int EMBEDS = 10;

    /** And at most five buttons in one row. */
    private static final int ROW = 5;

    /** And at most 25 options in one select. */
    private static final int OPTIONS = 25;

    private final GuildLanguages languages;
    private final DiscordRenderer messages;

    OnboardingMessage(final GuildLanguages languages, final DiscordRenderer messages) {
        this.languages = languages;
        this.messages = messages;
    }

    /** Posts or edits both messages; an empty channel id means that message is not kept. */
    void publish(final ManagedMessage managed, final String onboardingChannelId, final String changeChannelId) {
        managed.publish(KIND, onboardingChannelId, welcome());
        managed.publish(CHANGE_KIND, changeChannelId, change());
    }

    /** The welcome: one embed in the default language and a select that asks for nothing but the language. */
    ManagedMessage.Content welcome() {
        final Locale locale = languages.fallback().locale();
        final MessageEmbed embed = Card.of(
                        messages.format(locale, MESSAGES.onboarding().welcome().title()))
                .lead(messages.format(locale, MESSAGES.onboarding().welcome().lead()))
                .wide(
                        messages.format(locale, MESSAGES.onboarding().welcome().stepsHeading()),
                        messages.format(locale, MESSAGES.onboarding().welcome().steps()))
                .image("attachment://" + BANNER)
                .build();
        final StringSelectMenu menu = StringSelectMenu.create(Ids.ONBOARD_PICK_LANGUAGE)
                .setPlaceholder(
                        messages.format(locale, MESSAGES.onboarding().welcome().choose()))
                .addOptions(languages.all().stream()
                        .limit(OPTIONS)
                        .map(OnboardingMessage::option)
                        .toList())
                .build();
        return new ManagedMessage.Content(List.of(embed), List.of(ActionRow.of(menu)), BANNER);
    }

    /** The message to change later: an embed and a button per language, each opening the dialog in it. */
    ManagedMessage.Content change() {
        final List<MessageEmbed> embeds = new ArrayList<>();
        final List<Button> buttons = new ArrayList<>();
        for (final GuildLanguages.Language language :
                languages.all().stream().limit(EMBEDS).toList()) {
            final Locale locale = language.locale();
            final String button =
                    messages.format(locale, MESSAGES.onboarding().change().button());
            embeds.add(Card.of(messages.format(
                            locale, MESSAGES.onboarding().change().title()))
                    .field(
                            messages.format(
                                    locale, MESSAGES.onboarding().change().heading()),
                            messages.format(
                                    locale, MESSAGES.onboarding().change().how(button)))
                    .build());
            buttons.add(Button.primary(Ids.ONBOARD + language.tag(), button));
        }
        final List<ActionRow> rows = new ArrayList<>();
        for (int first = 0; first < buttons.size(); first += ROW) {
            rows.add(ActionRow.of(buttons.subList(first, Math.min(first + ROW, buttons.size()))));
        }
        return new ManagedMessage.Content(embeds, rows, null);
    }

    /** A language under its own name, with the flag of its country where it has one. */
    private static SelectOption option(final GuildLanguages.Language language) {
        final SelectOption option = SelectOption.of(language.ownName(), language.tag());
        return Locales.flagCountry(language.locale())
                .map(country -> option.withEmoji(Emoji.fromUnicode(Mark.flag(country))))
                .orElse(option);
    }
}
