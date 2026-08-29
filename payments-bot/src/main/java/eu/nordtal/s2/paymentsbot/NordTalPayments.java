package eu.nordtal.s2.paymentsbot;

import eu.nordtal.s2.paymentsbot.events.ContributionEventListeners;
import eu.nordtal.s2.paymentsbot.events.SlashCommandInteractionListener;
import eu.nordtal.s2.paymentsbot.service.PaymentProcessingService;
import lombok.Getter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class NordTalPayments {

    @Getter
    private final JDA jda;

    @Getter
    private final PaymentProcessingService paymentProcessingService;

    public NordTalPayments() throws InterruptedException {
        final String token = System.getenv("BOT_TOKEN");
        jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.getIntents(GatewayIntent.ALL_INTENTS))
                .build().awaitReady();

        jda.getPresence().setPresence(Activity.of(Activity.ActivityType.CUSTOM_STATUS, "Counting stacks 💶"), false);

        jda.updateCommands().addCommands(
                Commands.slash("send-contribution-embed", "Sends the contribution embed to the channel you are in.")
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("test-con", "Adds test contribution.")
                        .addOption(OptionType.INTEGER, "amount", "Amount", true)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("manual-con", "Adds a manual contribution.")
                        .addOption(OptionType.USER, "user", "User", true)
                        .addOption(OptionType.INTEGER, "amount", "Amount", true)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED)
        ).queue();

        paymentProcessingService = new PaymentProcessingService(jda);

        jda.addEventListener(new SlashCommandInteractionListener(this), new ContributionEventListeners());
    }

    public static void main(final String[] args) throws InterruptedException {
        new NordTalPayments();
    }


}
