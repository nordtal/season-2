package eu.nordtal.season.discordbot.onboarding;

import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.PrivateReplies;
import eu.nordtal.season.discordbot.Replies;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordbot.config.Configured;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordbot.roles.GuildRoles;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.jspecify.annotations.Nullable;

/**
 * Opens the confirm dialog of the choose channel and the settings dialog of the change channel.
 * The confirm dialog saves nothing before Confirm; the settings dialog saves each change at once.
 */
@Slf4j
public final class OnboardingFlow extends ListenerAdapter {

    /**
     * The state a dialog shows.
     *
     * @param confirming whether it is the confirm dialog, which asks the member to confirm
     * @param saved whether a change was just saved
     */
    public record Dialog(
            @Example("true") boolean confirming,
            @Example("false") boolean saved) {}

    static final DocumentSpec<Dialog> SETTINGS = DocumentSpec.message(
                    StoredDocuments.NAMESPACE,
                    "onboarding-settings",
                    "Onboarding",
                    Dialog.class,
                    Slot.select("countries"),
                    Slot.select("languages"),
                    Slot.button("confirm").optional(),
                    Slot.link("rules").optional())
            .titled("Country and language dialog");

    static final DocumentSpec<DocumentSpec.None> CONFIRMED = DocumentSpec.message(
                    StoredDocuments.NAMESPACE,
                    "onboarding-confirmed",
                    "Onboarding",
                    Slot.link("change").optional(),
                    Slot.link("rules").optional())
            .titled("Country and language confirmed");

    private final Onboarding onboarding;
    private final GuildLanguages languages;
    private final GuildRoles roles;
    private final DocumentRenderer documents;
    private final PrivateReplies replies;
    private final Executor lane;

    public OnboardingFlow(
            final Onboarding onboarding,
            final GuildLanguages languages,
            final GuildRoles roles,
            final DocumentRenderer documents,
            final PrivateReplies replies,
            final Executor lane) {
        this.onboarding = onboarding;
        this.languages = languages;
        this.roles = roles;
        this.documents = documents;
        this.replies = replies;
        this.lane = lane;
    }

    @Override
    public void onStringSelectInteraction(final StringSelectInteractionEvent event) {
        final Member member = event.getMember();
        final String id = event.getComponentId();
        if (member == null || event.getValues().isEmpty()) {
            return;
        }
        final String value = event.getValues().getFirst();
        final Locale client = event.getUserLocale().toLocale();
        if (id.equals(Ids.ONBOARD_COUNTRY)) {
            replies.defer(event);
            lane.execute(() -> open(event.getHook(), value, client));
        } else if (id.equals(Ids.ONBOARD_SET_COUNTRY) || id.equals(Ids.ONBOARD_SET_LANGUAGE)) {
            replies.deferEdit(event);
            final Choices.Kind kind = id.equals(Ids.ONBOARD_SET_COUNTRY) ? Choices.Kind.COUNTRY : Choices.Kind.LANGUAGE;
            lane.execute(() -> change(event.getHook(), member, kind, value, client));
        } else {
            Draft.parse(id).ifPresent(used -> {
                replies.deferEdit(event);
                lane.execute(() -> redraw(event.getHook(), used, value));
            });
        }
    }

    @Override
    public void onButtonInteraction(final ButtonInteractionEvent event) {
        final Member member = event.getMember();
        final String id = event.getComponentId();
        if (member == null) {
            return;
        }
        final Locale client = event.getUserLocale().toLocale();
        if (id.startsWith(Ids.ONBOARD_SETTINGS)) {
            replies.defer(event);
            lane.execute(() -> change(event.getHook(), member, null, null, client));
            return;
        }
        Draft.parse(id).filter(used -> used.field() == Draft.Field.CONFIRM).ifPresent(used -> {
            replies.deferEdit(event);
            lane.execute(() -> confirm(event.getHook(), member, used.draft()));
        });
    }

    /** Opens the confirm dialog for the country chosen in the choose channel. */
    private void open(final InteractionHook hook, final String country, final Locale client) {
        final Optional<Choices.Choice> chosen = onboarding.choices().byValue(Choices.Kind.COUNTRY, country);
        if (chosen.isEmpty()) {
            failed(hook, languages.forLocale(client).locale());
            return;
        }
        hook.editOriginal(draft(Draft.of(chosen.get())).edit()).queue();
    }

    private void redraw(final InteractionHook hook, final Draft.Used used, final String value) {
        final Draft next = switch (used.field()) {
            case COUNTRY ->
                onboarding
                        .choices()
                        .byValue(Choices.Kind.COUNTRY, value)
                        .map(used.draft()::withCountry)
                        .orElse(used.draft());
            case LANGUAGE -> used.draft().withLanguage(value);
            case CONFIRM -> used.draft();
        };
        hook.editOriginal(draft(next).edit()).queue();
    }

    private void confirm(final InteractionHook hook, final Member observed, final Draft draft) {
        final Locale reads = languageOf(draft).locale();
        try {
            final Member member = fresh(observed);
            final List<Choices.Choice> chosen = new ArrayList<>();
            onboarding.choices().byValue(Choices.Kind.COUNTRY, draft.country()).ifPresent(chosen::add);
            onboarding
                    .choices()
                    .byValue(Choices.Kind.LANGUAGE, draft.language())
                    .ifPresent(chosen::add);
            if (chosen.size() != 2 || !onboarding.choose(member, chosen)) {
                failed(hook, reads);
                return;
            }
            hook.editOriginal(confirmed(languageOf(draft), member.getGuild().getId())
                            .edit())
                    .queue();
        } catch (final RuntimeException failure) {
            log.warn("country confirmation failed: member={} cause={}", observed.getId(), failure.toString());
            failed(hook, reads);
        }
    }

    /** Saves one change from the settings dialog, or with no kind only shows the dialog. */
    private void change(
            final InteractionHook hook,
            final Member observed,
            final Choices.@Nullable Kind kind,
            final @Nullable String value,
            final Locale client) {
        Locale reads = language(observed, client).locale();
        try {
            Member member = fresh(observed);
            if (kind != null) {
                final Optional<Choices.Choice> chosen = onboarding.choices().byValue(kind, value);
                if (chosen.isEmpty() || !onboarding.choose(member, List.of(chosen.get()))) {
                    failed(hook, reads);
                    return;
                }
                member = fresh(observed);
            }
            final GuildLanguages.Language language = language(member, client);
            reads = language.locale();
            hook.editOriginal(settings(
                                    documents,
                                    languages,
                                    onboarding.choices(),
                                    language,
                                    held(member, Choices.Kind.COUNTRY)
                                            .map(Choices.Choice::value)
                                            .orElse(null),
                                    held(member, Choices.Kind.LANGUAGE)
                                            .map(Choices.Choice::value)
                                            .orElse(null),
                                    kind != null,
                                    member.getGuild().getId())
                            .edit())
                    .queue();
        } catch (final RuntimeException failure) {
            log.warn("country settings update failed: member={} cause={}", observed.getId(), failure.toString());
            failed(hook, reads);
        }
    }

    private DocumentRenderer.Drawn draft(final Draft draft) {
        return dialog(documents, languages, onboarding.choices(), draft);
    }

    /** Draws the confirm dialog in the draft's language, with both selects preset and the green Confirm button. */
    static DocumentRenderer.Drawn dialog(
            final DocumentRenderer documents,
            final GuildLanguages languages,
            final Choices choices,
            final Draft draft) {
        return documents.render(
                SETTINGS,
                new Dialog(true, false),
                Map.of(
                        "countries",
                        OnboardingMessage.countries(choices, draft.id(Draft.Field.COUNTRY), draft.country()),
                        "languages",
                        OnboardingMessage.languages(languages, draft.id(Draft.Field.LANGUAGE), draft.language()),
                        "confirm",
                        new DocumentRenderer.FilledButton(draft.id(Draft.Field.CONFIRM), ButtonStyle.SUCCESS),
                        "rules",
                        new DocumentRenderer.FilledNothing()),
                languages.byTag(draft.language()).orElse(languages.fallback()).locale());
    }

    /** Draws the settings dialog in the member's language, with the member's choices preset. */
    static DocumentRenderer.Drawn settings(
            final DocumentRenderer documents,
            final GuildLanguages languages,
            final Choices choices,
            final GuildLanguages.Language language,
            final @Nullable String country,
            final @Nullable String spoken,
            final boolean saved,
            final String guild) {
        return documents.render(
                SETTINGS,
                new Dialog(false, saved),
                Map.of(
                        "countries",
                        OnboardingMessage.countries(choices, Ids.ONBOARD_SET_COUNTRY, country),
                        "languages",
                        OnboardingMessage.languages(languages, Ids.ONBOARD_SET_LANGUAGE, spoken),
                        "confirm",
                        new DocumentRenderer.FilledNothing(),
                        "rules",
                        link(guild, language.rulesChannelId())),
                language.locale());
    }

    private DocumentRenderer.Drawn confirmed(final GuildLanguages.Language language, final String guild) {
        return documents.render(
                CONFIRMED,
                new DocumentSpec.None(),
                Map.of(
                        "change",
                        link(guild, onboarding.changeChannel()),
                        "rules",
                        link(guild, language.rulesChannelId())),
                language.locale());
    }

    private static DocumentRenderer.Filled link(final String guild, final String channel) {
        return Configured.isSet(channel)
                ? DocumentRenderer.FilledButton.link("https://discord.com/channels/" + guild + "/" + channel)
                : new DocumentRenderer.FilledNothing();
    }

    private void failed(final InteractionHook hook, final Locale reads) {
        hook.editOriginal(documents.reply(reads, Replies.ONBOARDING_FAILED).edit())
                .queue();
    }

    private GuildLanguages.Language languageOf(final Draft draft) {
        return languages.byTag(draft.language()).orElse(languages.fallback());
    }

    private static Member fresh(final Member member) {
        return member.getGuild()
                .retrieveMemberById(member.getId())
                .useCache(false)
                .complete();
    }

    private GuildLanguages.Language language(final Member member, final Locale client) {
        return held(member, Choices.Kind.LANGUAGE)
                .flatMap(choice -> languages.byTag(choice.value()))
                .orElseGet(() -> languages.forLocale(client));
    }

    private Optional<Choices.Choice> held(final Member member, final Choices.Kind kind) {
        final Set<String> ids = member.getRoles().stream().map(Role::getId).collect(Collectors.toSet());
        return onboarding
                .choices()
                .among(kind, ids, key -> roles.role(member.getGuild(), key).map(Role::getId))
                .stream()
                .findFirst();
    }
}
