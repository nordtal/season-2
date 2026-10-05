package eu.nordtal.s2.discordbot.onboarding;

import static eu.nordtal.s2.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.alert.DiscordRole;
import eu.nordtal.s2.discordbot.DiscordRenderer;
import eu.nordtal.s2.discordbot.Ids;
import eu.nordtal.s2.discordbot.config.Languages;
import eu.nordtal.s2.discordbot.roles.GuildRoles;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import org.jspecify.annotations.Nullable;

/**
 * The onboarding message's buttons: each opens a modal in its language to choose a language and a region.
 *
 * Choosing gives the two roles and nothing else; {@link Onboarding} takes the rest of their kind and writes the record.
 */
@Slf4j
public final class OnboardingFlow extends ListenerAdapter {

    private final Onboarding onboarding;
    private final Languages languages;
    private final GuildRoles roles;
    private final DiscordRenderer messages;
    private final String networkZone;
    private final Consumer<Alert> alerts;
    private final Executor lane;

    /**
     * Creates it.
     *
     * @param networkZone the zone a member without a region reads, offered first when it is a region
     * @param lane the lane {@link Onboarding} runs on, so a choice and its settling never overlap
     */
    public OnboardingFlow(
            final Onboarding onboarding,
            final Languages languages,
            final GuildRoles roles,
            final DiscordRenderer messages,
            final String networkZone,
            final Consumer<Alert> alerts,
            final Executor lane) {
        this.onboarding = onboarding;
        this.languages = languages;
        this.roles = roles;
        this.messages = messages;
        this.networkZone = networkZone;
        this.alerts = alerts;
        this.lane = lane;
    }

    @Override
    public void onButtonInteraction(final ButtonInteractionEvent event) {
        final Member member = event.getMember();
        if (!event.getComponentId().startsWith(Ids.ONBOARD) || member == null) {
            return;
        }
        final Languages.Language language = spoken(event.getComponentId(), Ids.ONBOARD);
        final Locale locale = language.locale();
        final Guild guild = member.getGuild();
        final Choices choices = onboarding.choices();
        final Set<String> held = member.getRoles().stream().map(Role::getId).collect(Collectors.toUnmodifiableSet());
        final List<SelectOption> spoken = options(guild, choices, Choices.Kind.LANGUAGE);
        final List<SelectOption> regions = options(guild, choices, Choices.Kind.REGION);
        if (spoken.isEmpty() || regions.isEmpty()) {
            log.warn("{} wanted to choose, but no language or no region has a role yet", member.getId());
            event.reply(messages.format(locale, MESSAGES.onboarding().failed()))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        final String client = Locales.tag(event.getUserLocale().toLocale());
        final Modal modal = Modal.create(
                        Ids.ONBOARD_MODAL + language.tag(),
                        messages.format(locale, MESSAGES.onboarding().modal().title()))
                .addComponents(
                        Label.of(
                                messages.format(
                                        locale, MESSAGES.onboarding().modal().language()),
                                menu(
                                        Ids.ONBOARD_LANGUAGE,
                                        spoken,
                                        held(guild, choices, Choices.Kind.LANGUAGE, held)
                                                .or(() -> offered(spoken, client))
                                                .or(() -> offered(spoken, Locales.tag(locale))))),
                        Label.of(
                                messages.format(
                                        locale, MESSAGES.onboarding().modal().region()),
                                menu(
                                        Ids.ONBOARD_REGION,
                                        regions,
                                        held(guild, choices, Choices.Kind.REGION, held)
                                                .or(() -> offered(regions, networkZone)))))
                .build();
        event.replyModal(modal).queue();
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
                        .editOriginal(
                                messages.format(asked, MESSAGES.onboarding().failed()))
                        .queue();
                return;
            }
            final boolean given = give(member, language.get(), DiscordRole.LANGUAGE)
                    && give(member, region.get(), DiscordRole.REGION);
            // Answered in the language just chosen, which is the one the member reads from now on.
            final Locale reads = given ? Locales.parse(tag) : asked;
            event.getHook()
                    .editOriginal(
                            given
                                    ? messages.format(
                                            reads,
                                            MESSAGES.onboarding()
                                                    .saved(
                                                            language.get().getName(),
                                                            region.get().getName()))
                                    : messages.format(
                                            reads, MESSAGES.onboarding().failed()))
                    .queue();
        });
    }

    /** Gives the role unless it is held, and returns whether the member holds it now. */
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

    /** The choices of a kind whose role the guild has, each under the role's name as it reads in Discord now. */
    private List<SelectOption> options(final Guild guild, final Choices choices, final Choices.Kind kind) {
        return choices.of(kind).stream()
                .flatMap(choice -> roles.role(guild, choice.key()).stream()
                        .map(role -> SelectOption.of(role.getName(), choice.value())))
                .toList();
    }

    /** The value of the choice of this kind the member holds, if any. */
    private Optional<String> held(
            final Guild guild, final Choices choices, final Choices.Kind kind, final Set<String> held) {
        return choices.among(kind, held, key -> roles.role(guild, key).map(Role::getId)).stream()
                .findFirst()
                .map(Choices.Choice::value);
    }

    private Optional<Role> role(final Guild guild, final Optional<Choices.Choice> choice) {
        return choice.flatMap(chosen -> roles.role(guild, chosen.key()));
    }

    private Languages.Language spoken(final String id, final String prefix) {
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
