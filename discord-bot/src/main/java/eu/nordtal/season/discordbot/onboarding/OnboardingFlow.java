package eu.nordtal.season.discordbot.onboarding;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.DiscordRole;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.Replies;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordbot.roles.GuildRoles;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.jspecify.annotations.Nullable;

/**
 * Handles the choices of the onboarding: language, region and the change dialog.
 * A choice only gives roles. {@link Onboarding} removes the other roles of the kind and the lock role.
 * It writes the record.
 */
@Slf4j
public final class OnboardingFlow extends ListenerAdapter {

    /** The answer to a language choice. It asks for the region in the chosen language. */
    static final DocumentSpec<DocumentSpec.None> REGION =
            DocumentSpec.message(StoredDocuments.NAMESPACE, "onboarding-region", "Onboarding", Slot.select("regions"));

    /** The dialog of the change message. It asks for the language and the region. */
    static final DocumentSpec<DocumentSpec.None> CHOICE = DocumentSpec.modal(
            StoredDocuments.NAMESPACE,
            "onboarding-choice",
            "Onboarding",
            DocumentSpec.None.class,
            Slot.input("language"),
            Slot.input("region"));

    private final Onboarding onboarding;
    private final GuildLanguages languages;
    private final GuildRoles roles;
    private final DocumentRenderer documents;
    private final String networkZone;
    private final Consumer<Alert> alerts;
    private final Executor lane;

    /**
     * @param networkZone the zone of a member without a region, which the dialog preselects when it is a region
     * @param lane the lane of {@link Onboarding}
     */
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
        this.alerts = alerts;
        this.lane = lane;
    }

    @Override
    public void onStringSelectInteraction(final StringSelectInteractionEvent event) {
        final String id = event.getComponentId();
        final boolean language = id.equals(Ids.ONBOARD_PICK_LANGUAGE);
        final Member member = event.getMember();
        if (!(language || id.startsWith(Ids.ONBOARD_PICK_REGION))
                || member == null
                || event.getValues().isEmpty()) {
            return;
        }
        final String value = event.getValues().getFirst();
        if (language) {
            event.deferReply(true).queue();
            lane.execute(() -> answerLanguage(event.getHook(), member, value));
        } else {
            final GuildLanguages.Language asked = spoken(id, Ids.ONBOARD_PICK_REGION);
            event.deferEdit().queue();
            lane.execute(() -> answerRegion(event.getHook(), member, asked, value));
        }
    }

    /** Gives the chosen language and answers in it. The answer asks for the region unless the member holds one. */
    private void answerLanguage(final InteractionHook hook, final Member member, final String tag) {
        final Guild guild = member.getGuild();
        final Choices choices = onboarding.choices();
        final Optional<Role> language = role(guild, choices.byValue(Choices.Kind.LANGUAGE, tag));
        final Locale reads =
                language.isPresent() ? Locales.parse(tag) : languages.fallback().locale();
        final boolean given = language.isPresent() && give(member, language.get(), DiscordRole.LANGUAGE);
        final Optional<Role> region = held(guild, choices, Choices.Kind.REGION, ids(member))
                .flatMap(zone -> role(guild, choices.byValue(Choices.Kind.REGION, zone)));
        final List<SelectOption> regions = options(guild, choices, Choices.Kind.REGION);
        switch (Answer.after(given, region.isPresent(), !regions.isEmpty())) {
            case ASK_REGION ->
                hook.editOriginal(region(documents, Ids.ONBOARD_PICK_REGION + tag, regions, reads)
                                .edit())
                        .queue();
            case SAVED ->
                hook.editOriginal(saved(reads, language.orElseThrow(), region.orElseThrow()))
                        .queue();
            case FAILED -> {
                log.warn(
                        "{} chose the language {}, which was not given or leaves no region to offer",
                        member.getId(),
                        tag);
                hook.editOriginal(reply(reads, DocumentRenderer.Reply.of(Replies.ONBOARDING_FAILED)))
                        .queue();
            }
        }
    }

    /** Gives the chosen region and replaces the answer to the language choice with the outcome, in that language. */
    private void answerRegion(
            final InteractionHook hook, final Member member, final GuildLanguages.Language asked, final String zone) {
        final Guild guild = member.getGuild();
        final Choices choices = onboarding.choices();
        final Optional<Role> region = role(guild, choices.byValue(Choices.Kind.REGION, zone));
        final boolean given = region.isPresent() && give(member, region.get(), DiscordRole.REGION);
        final Locale reads = asked.locale();
        final Optional<Role> language = role(guild, choices.byValue(Choices.Kind.LANGUAGE, asked.tag()));
        hook.editOriginal(
                        Answer.after(given, given, false) == Answer.SAVED && language.isPresent()
                                ? saved(reads, language.get(), region.orElseThrow())
                                : reply(reads, DocumentRenderer.Reply.of(Replies.ONBOARDING_FAILED)))
                .queue();
    }

    /** Returns the region question in {@code reads}. Its select offers {@code regions} under the id {@code id}. */
    static DocumentRenderer.Drawn region(
            final DocumentRenderer documents, final String id, final List<SelectOption> regions, final Locale reads) {
        final List<DocumentRenderer.Option> options = regions.stream()
                .map(option -> new DocumentRenderer.Option(option.getValue(), option.getLabel(), Map.of(), null))
                .toList();
        return documents.render(
                REGION,
                new DocumentSpec.None(),
                Map.of("regions", new DocumentRenderer.FilledSelect(id, options)),
                reads);
    }

    private MessageEditData saved(final Locale reads, final Role language, final Role region) {
        return reply(
                reads,
                new DocumentRenderer.Reply<>(
                        Replies.ONBOARDING_SAVED, new Replies.Saved(language.getName(), region.getName())));
    }

    private MessageEditData reply(final Locale reads, final DocumentRenderer.Reply<?> text) {
        return documents.reply(reads, text).edit();
    }

    private static Set<String> ids(final Member member) {
        return member.getRoles().stream().map(Role::getId).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void onButtonInteraction(final ButtonInteractionEvent event) {
        final Member member = event.getMember();
        if (!event.getComponentId().startsWith(Ids.ONBOARD) || member == null) {
            return;
        }
        final GuildLanguages.Language language = spoken(event.getComponentId(), Ids.ONBOARD);
        final Locale locale = language.locale();
        final Guild guild = member.getGuild();
        final Choices choices = onboarding.choices();
        final Set<String> held = ids(member);
        final List<SelectOption> spoken = options(guild, choices, Choices.Kind.LANGUAGE);
        final List<SelectOption> regions = options(guild, choices, Choices.Kind.REGION);
        if (spoken.isEmpty() || regions.isEmpty()) {
            log.warn("{} wanted to choose, but no language or no region has a role yet", member.getId());
            event.reply(documents
                            .reply(locale, DocumentRenderer.Reply.of(Replies.ONBOARDING_FAILED))
                            .create())
                    .setEphemeral(true)
                    .queue();
            return;
        }
        final String client = Locales.tag(event.getUserLocale().toLocale());
        final Optional<String> spokenNow = held(guild, choices, Choices.Kind.LANGUAGE, held)
                .or(() -> offered(spoken, client))
                .or(() -> offered(spoken, Locales.tag(locale)));
        final Optional<String> regionNow =
                held(guild, choices, Choices.Kind.REGION, held).or(() -> offered(regions, networkZone));
        event.replyModal(documents.modal(
                        CHOICE,
                        Ids.ONBOARD_MODAL + language.tag(),
                        new DocumentSpec.None(),
                        Map.of(
                                "language",
                                new DocumentRenderer.FilledInput(
                                        placeholder -> menu(Ids.ONBOARD_LANGUAGE, spoken, spokenNow)),
                                "region",
                                new DocumentRenderer.FilledInput(
                                        placeholder -> menu(Ids.ONBOARD_REGION, regions, regionNow))),
                        locale))
                .queue();
    }

    @Override
    public void onModalInteraction(final ModalInteractionEvent event) {
        final Member member = event.getMember();
        if (!event.getModalId().startsWith(Ids.ONBOARD_MODAL) || member == null) {
            return;
        }
        final Locale asked = spoken(event.getModalId(), Ids.ONBOARD_MODAL).locale();
        final String tag = first(event.getValue(Ids.ONBOARD_LANGUAGE));
        final String zone = first(event.getValue(Ids.ONBOARD_REGION));
        event.deferReply(true).queue();
        lane.execute(() -> {
            final Choices choices = onboarding.choices();
            final Optional<Role> language = role(member.getGuild(), choices.byValue(Choices.Kind.LANGUAGE, tag));
            final Optional<Role> region = role(member.getGuild(), choices.byValue(Choices.Kind.REGION, zone));
            if (language.isEmpty() || region.isEmpty()) {
                log.warn("{} chose {} and {}, of which one has no role", member.getId(), tag, zone);
                event.getHook()
                        .editOriginal(reply(asked, DocumentRenderer.Reply.of(Replies.ONBOARDING_FAILED)))
                        .queue();
                return;
            }
            final boolean given = give(member, language.get(), DiscordRole.LANGUAGE)
                    && give(member, region.get(), DiscordRole.REGION);
            // The answer is in the chosen language.
            final Locale reads = given ? Locales.parse(tag) : asked;
            event.getHook()
                    .editOriginal(
                            given
                                    ? saved(reads, language.get(), region.get())
                                    : reply(reads, DocumentRenderer.Reply.of(Replies.ONBOARDING_FAILED)))
                    .queue();
        });
    }

    /** Gives the role unless the member holds it. Returns whether the member holds the role. */
    private boolean give(final Member member, final Role role, final DiscordRole kind) {
        if (member.getRoles().contains(role)) {
            return true;
        }
        try {
            member.getGuild().addRoleToMember(member, role).complete();
            return true;
        } catch (final RuntimeException failure) {
            log.warn("Could not give {} the role {}: {}", member.getId(), role.getName(), failure.toString());
            alerts.accept(GuildRoles.notChanged(kind, true, DiscordId.of(member.getId()), failure));
            return false;
        }
    }

    /** Returns the choices of the kind whose role the guild has, labelled with the current role name. */
    private List<SelectOption> options(final Guild guild, final Choices choices, final Choices.Kind kind) {
        return choices.of(kind).stream()
                .flatMap(choice -> roles.role(guild, choice.key()).stream()
                        .map(role -> SelectOption.of(role.getName(), choice.value())))
                .toList();
    }

    private Optional<String> held(
            final Guild guild, final Choices choices, final Choices.Kind kind, final Set<String> held) {
        return choices.among(kind, held, key -> roles.role(guild, key).map(Role::getId)).stream()
                .findFirst()
                .map(Choices.Choice::value);
    }

    private Optional<Role> role(final Guild guild, final Optional<Choices.Choice> choice) {
        return choice.flatMap(chosen -> roles.role(guild, chosen.key()));
    }

    private GuildLanguages.Language spoken(final String id, final String prefix) {
        return languages.byTag(id.substring(prefix.length())).orElse(languages.fallback());
    }

    private static Optional<String> offered(final List<SelectOption> options, final String value) {
        return options.stream()
                .map(SelectOption::getValue)
                .filter(value::equals)
                .findFirst();
    }

    private static StringSelectMenu menu(
            final String id, final List<SelectOption> options, final Optional<String> preselected) {
        final StringSelectMenu.Builder menu =
                StringSelectMenu.create(id).addOptions(options).setRequired(true);
        return preselected.map(menu::setDefaultValues).orElse(menu).build();
    }

    private static String first(final @Nullable ModalMapping value) {
        return value == null || value.getAsStringList().isEmpty()
                ? ""
                : value.getAsStringList().getFirst();
    }
}
