package eu.nordtal.season.discordbot.onboarding;

import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.Replies;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordbot.roles.GuildRoles;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.jspecify.annotations.Nullable;

/** Applies country and language choices and replies with the member's settings. */
@Slf4j
public final class OnboardingFlow extends ListenerAdapter {
    public record Settings(
            @Example("English") String language,
            @Example("Europe/Berlin") String zone) {}

    static final DocumentSpec<Settings> SETTINGS = DocumentSpec.message(
                    StoredDocuments.NAMESPACE,
                    "onboarding-settings",
                    "Onboarding",
                    Settings.class,
                    Slot.select("countries"),
                    Slot.link("rules").optional(),
                    Slot.languages("others"),
                    Slot.button("language"))
            .titled("Country and language settings");
    private final Onboarding onboarding;
    private final GuildLanguages languages;
    private final GuildRoles roles;
    private final DocumentRenderer documents;
    private final String networkZone;
    private final Executor lane;

    public OnboardingFlow(
            final Onboarding onboarding,
            final GuildLanguages languages,
            final GuildRoles roles,
            final DocumentRenderer documents,
            final String networkZone,
            final Consumer<Alert> alerts,
            final Executor lane) {
        this.onboarding = onboarding;
        this.languages = languages;
        this.roles = roles;
        this.documents = documents;
        this.networkZone = networkZone;
        this.lane = lane;
    }

    @Override
    public void onStringSelectInteraction(final StringSelectInteractionEvent event) {
        final Member member = event.getMember();
        if (!event.getComponentId().equals(Ids.ONBOARD_COUNTRY)
                || member == null
                || event.getValues().isEmpty()) {
            return;
        }
        final String country = event.getValues().getFirst();
        event.deferReply(true).queue();
        lane.execute(() -> answer(
                event.getHook(),
                member,
                Choices.Kind.COUNTRY,
                country,
                event.getUserLocale().toLocale()));
    }

    @Override
    public void onButtonInteraction(final ButtonInteractionEvent event) {
        final Member member = event.getMember();
        final String id = event.getComponentId();
        if (member == null
                || !(id.equals(Ids.ONBOARD_SETTINGS)
                        || id.startsWith(Ids.ONBOARD)
                        || id.startsWith(Ids.ONBOARD_LANGUAGE))) {
            return;
        }
        event.deferReply(true).queue();
        lane.execute(() -> answer(
                event.getHook(),
                member,
                Choices.Kind.LANGUAGE,
                id.startsWith(Ids.ONBOARD_LANGUAGE) ? id.substring(Ids.ONBOARD_LANGUAGE.length()) : null,
                event.getUserLocale().toLocale()));
    }

    private void answer(
            final InteractionHook hook,
            final Member observed,
            final Choices.Kind kind,
            final @Nullable String value,
            final Locale client) {
        Locale reads = language(observed, client).locale();
        try {
            final Member member = observed.getGuild()
                    .retrieveMemberById(observed.getId())
                    .useCache(false)
                    .complete();
            if (value != null) {
                final Optional<Choices.Choice> chosen = onboarding.choices().byValue(kind, value);
                if (chosen.isEmpty() || !onboarding.choose(member, chosen.get())) {
                    hook.editOriginal(documents
                                    .reply(reads, Replies.ONBOARDING_FAILED)
                                    .edit())
                            .queue();
                    return;
                }
            }
            final Member current = observed.getGuild()
                    .retrieveMemberById(observed.getId())
                    .useCache(false)
                    .complete();
            final GuildLanguages.Language language = language(current, client);
            reads = language.locale();
            final Optional<Choices.Choice> country = held(current, Choices.Kind.COUNTRY);
            hook.editOriginal(settings(
                                    documents,
                                    languages,
                                    onboarding.choices(),
                                    language,
                                    country.map(Choices.Choice::value).orElse(null),
                                    country.map(Choices.Choice::zone)
                                            .filter(zone -> !zone.isEmpty())
                                            .orElse(networkZone),
                                    current.getGuild().getId())
                            .edit())
                    .queue();
        } catch (final RuntimeException failure) {
            log.warn("country settings failed: member={} cause={}", observed.getId(), failure.toString());
            hook.editOriginal(documents.reply(reads, Replies.ONBOARDING_FAILED).edit())
                    .queue();
        }
    }

    static DocumentRenderer.Drawn settings(
            final DocumentRenderer documents,
            final GuildLanguages languages,
            final Choices choices,
            final GuildLanguages.Language language,
            final @Nullable String country,
            final String zone,
            final String guild) {
        return documents.render(
                SETTINGS,
                new Settings(language.ownName(), zone),
                Map.of(
                        "countries",
                        OnboardingMessage.countries(choices, country),
                        "rules",
                        language.rulesChannelId().isBlank()
                                ? new DocumentRenderer.FilledNothing()
                                : DocumentRenderer.FilledButton.link(
                                        "https://discord.com/channels/" + guild + "/" + language.rulesChannelId()),
                        "others",
                        new DocumentRenderer.FilledLanguages(languages.all().stream()
                                .filter(other -> !other.tag().equals(language.tag()))
                                .map(GuildLanguages.Language::locale)
                                .toList()),
                        "language",
                        new DocumentRenderer.FilledButton(Ids.ONBOARD_LANGUAGE)),
                language.locale());
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
