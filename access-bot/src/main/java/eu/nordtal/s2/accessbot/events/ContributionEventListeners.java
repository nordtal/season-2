package eu.nordtal.s2.accessbot.events;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import eu.nordtal.s2.accessbot.model.ContributionTier;
import eu.nordtal.s2.accessbot.model.PaymentMethod;
import eu.nordtal.s2.accessbot.model.SetupFlow;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.apache.commons.lang3.ObjectUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ContributionEventListeners extends ListenerAdapter {

    private final Cache<@NotNull String, @NotNull SetupFlow> cache = CacheBuilder.newBuilder()
            .expireAfterAccess(20, TimeUnit.MINUTES)
            .build();

    @Override
    public void onButtonInteraction(@NotNull final ButtonInteractionEvent event) {
        if ("contribution_init".equals(event.getComponentId())) {
            final String senderId = event.getUser().getId();
            final SetupFlow oldFlow = cache.getIfPresent(senderId);
            if (oldFlow != null) {
                try {
                    event.getHook().deleteMessageById(oldFlow.getFlowMessageId()).queue();
                } catch (Exception ignored) {}
            }
            CompletableFuture.runAsync(() -> {
                final String messageId = sendFlowMessage(event);
                cache.put(senderId, new SetupFlow(messageId, senderId, senderId, null, null));
            });
            return;
        }
        if ("contribution_submit".equals(event.getComponentId())) {
            final SetupFlow flow = cache.getIfPresent(event.getUser().getId());
            if (flow == null || !flow.isComplete()) {
                event.reply("Please complete the contribution setup first.").setEphemeral(true).queue();
                return;
            }
            event.deferReply(true).queue();
            CompletableFuture.runAsync(() -> {
                final User userById = event.getJDA().retrieveUserById(flow.getReceiverId()).complete();
                if (userById == null) {
                    event.reply("The receiver you specified could not be found.").setEphemeral(true).queue();
                    return;
                }
                final Role role = event.getJDA().getRoleById(flow.getContributionTier().getRoleId());
                if (role == null) {
                    event.reply("The role you specified could not be found.").setEphemeral(true).queue();
                    return;
                }
                event.getInteraction().getMessage().delete().queue();

                final User receiver = event.getJDA().retrieveUserById(flow.getReceiverId()).complete();
                final Role rewardRole = event.getJDA().getRoleById(flow.getContributionTier().getRoleId());
                if (ObjectUtils.anyNull(receiver, rewardRole)) {
                    return;
                }
                event.getHook().sendMessage(String.format("""
                                ### Contribution details
                                You are about to contribute **%d€** to the nordtal server bills.
                                - **Reward:** %s
                                - **Receiver:** %s
                                
                                You chose to pay with %s:
                                %s
                                """,
                        flow.getContributionTier().getEuroAmount(),
                        rewardRole.getAsMention(),
                        receiver.getAsMention(),
                        flow.getPaymentMethod().getDisplayName(),
                        flow.getPaymentMethod().getDetailsGenerator().apply(flow)
                )).setEphemeral(true).queue();
            });
        }
    }

    @Override
    public void onStringSelectInteraction(@NotNull final StringSelectInteractionEvent event) {
        final String senderId = event.getUser().getId();
        final SetupFlow flow = cache.getIfPresent(senderId);
        if ("contribution_tier".equals(event.getComponentId())) {
            if (flow == null) {
                event.getInteraction().getMessage().delete().queue();
                event.reply("Setup expired. Please start a new contribution!").setEphemeral(true).queue();
                return;
            }
            flow.setContributionTier(ContributionTier.valueOf(event.getSelectedOptions().getFirst().getValue()));
            event.deferEdit().queue();
        } else if ("contribution_method".equals(event.getComponentId())) {
            if (flow == null) {
                event.getInteraction().getMessage().delete().queue();
                event.reply("Setup expired. Please start a new contribution!").setEphemeral(true).queue();
                return;
            }
            flow.setPaymentMethod(PaymentMethod.valueOf(event.getSelectedOptions().getFirst().getValue()));
            event.deferEdit().queue();
        }
    }

    @Override
    public void onEntitySelectInteraction(@NotNull final EntitySelectInteractionEvent event) {
        if (event.getComponentId().equals("contribution_receiver")) {
            final String senderId = event.getUser().getId();
            final String receiverId = event.getValues().getFirst().getId();
            final SetupFlow flow = cache.getIfPresent(senderId);
            if (flow == null) {
                event.getInteraction().getMessage().delete().queue();
                event.reply("Setup expired. Please start a new contribution!").setEphemeral(true).queue();
                return;
            }
            flow.setReceiverId(receiverId);
            event.deferEdit().queue();
        }
    }

    private String sendFlowMessage(final GenericComponentInteractionCreateEvent event) {
        event.reply("Please choose your contribution setup. Only change the user field below if you are trying to contribute for someone else.")
                .setEphemeral(true)
                .addComponents(ActionRow.of(
                        EntitySelectMenu.create("contribution_receiver", EntitySelectMenu.SelectTarget.USER)
                                .setRequiredRange(1, 1)
                                .setPlaceholder("Choose the person you want to receive the contribution role")
                                .setDefaultValues(EntitySelectMenu.DefaultValue.user(event.getUser().getId()))
                                .build()
                )).addComponents(ActionRow.of(
                        StringSelectMenu.create("contribution_tier")
                                .addOptions(Arrays.stream(ContributionTier.values())
                                        .map(tier -> SelectOption.of(
                                                tier.selectLabel(), tier.name()
                                        )).toList())
                                .setPlaceholder("Choose your contribution amount")
                                .build()
                )).addComponents(ActionRow.of(
                        StringSelectMenu.create("contribution_method")
                                .addOptions(Arrays.stream(PaymentMethod.values())
                                        .map(method -> SelectOption.of(
                                                method.getDisplayName(), method.name()
                                        )).toList())
                                .setPlaceholder("Choose your payment method")
                                .build()
                )).addComponents(ActionRow.of(
                        Button.of(ButtonStyle.PRIMARY, "contribution_submit", "Submit details")
                )).complete();
        return event.getHook().retrieveOriginal().complete().getId();
    }

}
