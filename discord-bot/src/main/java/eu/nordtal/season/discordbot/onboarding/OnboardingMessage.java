package eu.nordtal.season.discordbot.onboarding;

import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.discordbot.DiscordRenderer;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.LayoutRenderer;
import eu.nordtal.season.discordbot.ManagedMessage;
import eu.nordtal.season.discordbot.Mark;
import eu.nordtal.season.discordbot.StoredLayouts;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.spec.layout.Element;
import eu.nordtal.season.messages.spec.layout.Layout;
import eu.nordtal.season.messages.spec.layout.Line;
import eu.nordtal.season.messages.spec.layout.Part;
import java.util.List;
import java.util.Map;

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

    /** The welcome: one box in the default language over its banner, and a select that asks only for the language. */
    static final Layout WELCOME = Layout.discord(
            "access",
            "onboarding-welcome",
            "Onboarding",
            List.of(Element.select("language", "access/onboarding.welcome.choose")),
            Part.container(
                    Tone.BRAND,
                    Part.text(Line.title("access/onboarding.welcome.title")),
                    Part.text(Line.text("access/onboarding.welcome.lead")),
                    Part.text(
                            Line.heading("access/onboarding.welcome.steps-heading"),
                            Line.text("access/onboarding.welcome.steps")),
                    Part.gallery("attachment://onboarding.png")),
            Part.row("language"));

    /** The message to change later: a box and a button per language, each button opening the dialog in it. */
    static final Layout CHANGE = Layout.discord(
            "access",
            "onboarding-change",
            "Onboarding",
            List.of(Element.languages("each"), Element.button("change", "access/onboarding.change.button")),
            Part.languages(
                    "each",
                    Part.container(
                            Tone.BRAND,
                            Part.text(
                                    Line.title("access/onboarding.change.title"),
                                    Line.heading("access/onboarding.change.heading"),
                                    Line.text("access/onboarding.change.how"))),
                    Part.row("change")));

    private final GuildLanguages languages;
    private final LayoutRenderer renderer;

    OnboardingMessage(final GuildLanguages languages, final DiscordRenderer messages, final StoredLayouts layouts) {
        this.languages = languages;
        this.renderer = new LayoutRenderer(messages, layouts);
    }

    /** Posts or edits both messages; an empty channel id means that message is not kept. */
    void publish(final ManagedMessage managed, final String onboardingChannelId, final String changeChannelId) {
        managed.publish(KIND, onboardingChannelId, welcome());
        managed.publish(CHANGE_KIND, changeChannelId, change());
    }

    LayoutRenderer.Drawn welcome() {
        final List<LayoutRenderer.Option> options = languages.all().stream()
                .map(language -> new LayoutRenderer.Option(
                        language.tag(),
                        language.ownName(),
                        Map.of(),
                        Locales.flagCountry(language.locale()).map(Mark::flag).orElse(null)))
                .toList();
        return renderer.render(
                WELCOME,
                new LayoutRenderer.Filling(
                        Map.of(),
                        Map.of("language", new LayoutRenderer.FilledSelect(Ids.ONBOARD_PICK_LANGUAGE, options))),
                languages.fallback().locale());
    }

    LayoutRenderer.Drawn change() {
        return renderer.render(
                CHANGE,
                new LayoutRenderer.Filling(
                        Map.of("button", MESSAGES.onboarding().change().button()),
                        Map.of(
                                "each",
                                new LayoutRenderer.FilledLanguages(languages.all().stream()
                                        .map(GuildLanguages.Language::locale)
                                        .toList()),
                                "change",
                                new LayoutRenderer.FilledButton(Ids.ONBOARD))),
                languages.fallback().locale());
    }
}
