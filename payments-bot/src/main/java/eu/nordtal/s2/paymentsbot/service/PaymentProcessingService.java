package eu.nordtal.s2.paymentsbot.service;

import com.bunq.sdk.model.generated.endpoint.PaymentApiObject;
import eu.nordtal.s2.paymentsbot.config.PaymentProcessingSpec;
import eu.nordtal.s2.paymentsbot.model.ContributionTier;
import eu.nordtal.s2.paymentsbot.persistence.model.Contribution;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service that periodically polls the bunq account for new payments and assigns contribution
 * roles to users.
 * <p>
 * The configuration is handed in rather than loaded here. This class used to call
 * {@code Configs.load(...)} in its own constructor, catch the failure, log it and carry on with
 * {@code new PaymentProcessingConfig()} - so a broken or mistyped config file started the bot
 * against default Discord channel ids instead of stopping it. Loading now happens once, in
 * {@code NordTalPayments}, and a failure there stops the process.
 */
@Slf4j
public class PaymentProcessingService implements AutoCloseable {

    private static final long TEST_CONTRIBUTION_DURATION_SECONDS = Duration.ofMinutes(1).toSeconds();
    private static final long DEFAULT_CONTRIBUTION_DURATION_SECONDS = Duration.ofDays(30).toSeconds();

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final PaymentProcessingSpec config;
    private final JDA jda;
    private final ContributionService contributionService;

    /**
     * @param config the payment processing configuration, already loaded and validated. It used
     *               to be loaded in here, and a failure was swallowed - see the class javadoc.
     */
    public PaymentProcessingService(final JDA jda, final ContributionService contributionService,
                                    final PaymentProcessingSpec config) {
        this.jda = jda;
        this.contributionService = contributionService;
        this.config = config;
        schedule();
    }

    public void addTestContribution(final String userId, final int amount) {
        final Contribution contribution = new Contribution(null, userId, amount, LocalDateTime.now(),
                TEST_CONTRIBUTION_DURATION_SECONDS, -1L);
        if (amount < 5) {
            contribution.setDurationSeconds(0);
        }
        contributionService.save(contribution);
        log.info("Added test contribution: {} {} {}", contribution.getId(), userId, amount);
    }

    private void schedule() {
        executor.scheduleAtFixedRate(() -> {
            checkPayments();
            updateRoles();
        }, 0, config.checkIntervalSeconds(), TimeUnit.SECONDS);
        executor.scheduleAtFixedRate(this::updateBalanceChannel, 1, 5, TimeUnit.MINUTES);
    }

    private void checkPayments() {
        try {
            final Set<Long> processedPaymentIds = contributionService.allProcessedPaymentIds();
            final List<PaymentApiObject> allPayments = BunqService.listPayments(50).stream()
                    .filter(payment -> Double.parseDouble(payment.getAmount().getValue()) > 0)
                    .toList();
            final List<PaymentApiObject> newPayments = allPayments.stream()
                    .filter(payment -> !processedPaymentIds.contains(payment.getId()))
                    .toList();
            newPayments.forEach(this::handlePayment);
        } catch (Exception e) {
            log.error("Error while checking payments", e);
        }
    }

    public void handlePayment(final PaymentApiObject payment) {
        final String description = payment.getDescription() != null ? payment.getDescription() : "";
        final String trimmed = description.replaceAll("\\s", "");
        final Matcher m = Pattern.compile("\\d+:\\d+").matcher(trimmed);
        if (!m.find()) {
            return;
        }
        log.info("Processing new payment: {}€, text: '{}'", payment.getAmount().getValue(), payment.getDescription());
        final String[] parts = m.group().split(":");
        final String initiatorId = parts[0];
        final String receiverId = parts[1];
        final float amount;
        try {
            amount = Float.parseFloat(payment.getAmount().getValue());
        } catch (NumberFormatException e) {
            log.warn("Unable to parse payment amount '{}'", payment.getAmount().getValue());
            return;
        }
        log.info("Processing payment {} - {}€ from {} to {}", payment.getId(), amount, initiatorId, receiverId);
        final Contribution unpersisted = new Contribution(null, receiverId, amount, LocalDateTime.now(),
                amount < 5 ? 0 : DEFAULT_CONTRIBUTION_DURATION_SECONDS, payment.getId());
        try {
            final Contribution contribution = contributionService.save(unpersisted);
            final ContributionTier tier = contribution.contributionTier();
            if (tier == null) {
                log.warn("No tier found for contribution: {}, {}", contribution.getId(), contribution.getEuroAmount());
                return;
            }
            final Role role = jda.getRoleById(tier.getRoleId());
            if (role == null) {
                throw new RuntimeException("Unable to find role for contribution tier: " + contribution.contributionTier());
            }
            final Member member = role.getGuild().retrieveMemberById(receiverId).complete();
            if (member == null) {
                throw new RuntimeException("Unable to find member for contribution: " + receiverId);
            }
            final MessageChannel channel = jda.getTextChannelById(config.confirmationChannelId());
            if (channel != null) {
                final User initiator = jda.retrieveUserById(initiatorId).complete();
                final String msg;
                if (initiatorId.equals(receiverId)) {
                    msg = String.format("%s has contributed %.2f€ to the server. As compensation they received the %s role.",
                            member.getAsMention(), amount, role.getAsMention());
                } else {
                    final String initiatorMention = initiator == null ? "Someone" : initiator.getAsMention();
                    msg = String.format("%s has contributed %.2f€ in the name of %s. %s has received the %s role as a compensation.",
                            initiatorMention, amount, member.getAsMention(), member.getAsMention(), role.getAsMention());
                }
                channel.sendMessage(msg).queue();
            }
        } catch (Exception e) {
            log.warn("Could not assign role for payment {}", payment.getId(), e);
        }
    }

    private void updateRoles() {
        try {
            jda.getGuilds().forEach(guild -> guild.loadMembers().get().forEach(member -> {
                if (member.getUser().isBot()) {
                    return;
                }

                final ContributionTier activeTier = contributionService.highestActiveTier(member.getId());
                final Role desiredRole = activeTier == null ? null : guild.getRoleById(activeTier.getRoleId());

                for (ContributionTier tier : ContributionTier.values()) {
                    final Role role = guild.getRoleById(tier.getRoleId());
                    if (role == null) {
                        continue;
                    }
                    if (!role.equals(desiredRole)) {
                        if (member.getRoles().contains(role)) {
                            log.info("Removing outdated role {} from {}", role.getName(), member.getUser().getId());
                            guild.removeRoleFromMember(member, role).queue();
                        }
                    }
                }

                if (desiredRole != null && !member.getRoles().contains(desiredRole)) {
                    log.info("Assigning role {} to {}", desiredRole.getName(), member.getUser().getId());
                    guild.addRoleToMember(member, desiredRole).queue();
                }
            }));
        } catch (Exception e) {
            log.error("Error while updating roles", e);
        }
    }

    private void updateBalanceChannel() {
        final String channelId = config.balance().channelId();
        final VoiceChannel ch = jda.getVoiceChannelById(channelId);
        if (ch == null) {
            log.error("Voice channel {} not found", channelId);
            return;
        }
        final String currentName = ch.getName();
        final String newName = String.format(config.balance().nameFormat(), BunqService.balanceStr());
        if (!newName.equals(currentName)) {
            log.info("Old balance channel's name '{}' is not equal '{}', updating.", currentName, newName);
            ch.getManager().setName(newName).queue();
        }
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}

