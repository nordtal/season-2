package eu.nordtal.s2.paymentsbot.events;

import com.bunq.sdk.model.generated.endpoint.PaymentApiObject;
import com.bunq.sdk.model.generated.object.AmountObject;
import eu.nordtal.s2.paymentsbot.NordTalPayments;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.entities.EmbedType;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

@Slf4j
public class SlashCommandInteractionListener extends ListenerAdapter {

    private final NordTalPayments nordTalPayments;

    public SlashCommandInteractionListener(final NordTalPayments nordTalPayments) {
        this.nordTalPayments = nordTalPayments;
    }

    @Override
    public void onSlashCommandInteraction(@NotNull final SlashCommandInteractionEvent event) {
        if (event.getFullCommandName().equals("send-contribution-embed")) {
            event.getChannel().sendMessage("").addEmbeds(
                    new MessageEmbed(null, "Contribution Info",
                            """
                                    🛟 **A __one-time__ contribution** (min. 3€) helps cover our server bills.
                                    *100% of contributions go towards server costs!*
                                    
                                    :military_medal: More generous contributers can acquire more glorious __titles__ (roles) in nordtal.
                                    
                                    **Contribution Roles**
                                    3€・<@&1404916314669191178>
                                    5€・<@&1400967326853238834>
                                    7€・<@&1400966995767464017>
                                    9€・<@&1400967795969228820>
                                    
                                    :compass: You will be able to start playing as soon as you have received the role worth your contribution. We process payments instantly on our side.* **Welcome to...**""",
                            EmbedType.ARTICLE, null, 3431092, null, null, null, null, null, new MessageEmbed.ImageInfo("https://media.discordapp.net/attachments/1405569148754329753/1414691481129848872/WhatsApp_Image_2025-08-18_at_19.33.58_1.jpeg?ex=68c1cf1c&is=68c07d9c&hm=ceab6bb43eaa4c8762e0adeaa4ee006c38f9dbb4fdabc87115ff0fd24c9f0e81&=&format=webp&width=3313&height=829", null, 3313, 829), List.of()
                    ),
                    new MessageEmbed(null, "Contribute",
                            """
                                    Available  methods are: **Credit or Debit Card** and **Bank Transfer**.
                                    Click one of the buttons below to make a contribution for yourself or in someone else's name.
                                    
                                    *\\* All payments are processed right away. Only bank transfers can take a few days if your bank does not process payments instantly. Ours does ;)*""",
                            EmbedType.ARTICLE, null, 3431092, null, null, null, null, new MessageEmbed.Footer("Thank you in advance for you contribution!", "https://cdn.discordapp.com/attachments/647838782036377611/1412204477636808845/output-onlinepngtools.png?ex=68c154a8&is=68c00328&hm=432e1773b39db40109bd0ba5deb275ce92b84c016df2c46da0a31a6177abda61&", null), null, List.of()
                    )
                    ).addComponents(ActionRow.of(
                    Button.of(ButtonStyle.PRIMARY, "contribution_init", "Start contribution")
            )).queue();
            event.reply("Done.").setEphemeral(true).queue();
        } else if (event.getFullCommandName().equals("test-con")) {
            final int amount = Objects.requireNonNull(event.getOption("amount")).getAsInt();
            final String id = event.getUser().getId();
            event.reply("Done.").setEphemeral(true).queue();
            log.warn("wtf");
            try {
                nordTalPayments.getPaymentProcessingService().addTestContribution(id, amount);
            } catch (final Exception e) {
                log.error("Error while adding test contribution", e);
            }
        } else if (event.getFullCommandName().equals("manual-con")) {
            final double amount = Objects.requireNonNull(event.getOption("amount")).getAsDouble();
            final String id = Objects.requireNonNull(event.getOption("user")).getAsUser().getId();
            final PaymentApiObject paymentApiObject = new PaymentApiObject();
            final AmountObject amountObject = new AmountObject();
            amountObject.setValue(String.format("%s", amount));
            paymentApiObject.setAmount(amountObject);
            paymentApiObject.setDescription(String.format("%s:%s", id, id));
            paymentApiObject.setId(-1L);
            nordTalPayments.getPaymentProcessingService().handlePayment(paymentApiObject);
            event.reply("Done.").setEphemeral(true).queue();
        }
    }
}
