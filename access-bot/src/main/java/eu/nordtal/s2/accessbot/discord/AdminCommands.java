package eu.nordtal.s2.accessbot.discord;

import eu.nordtal.s2.accessbot.bunq.Money;
import eu.nordtal.s2.accessbot.payment.PaymentRequest;
import eu.nordtal.s2.accessbot.payment.PaymentRequests;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessGrant;
import eu.nordtal.s2.common.access.AccessSource;
import eu.nordtal.s2.common.message.Messages;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * The admin surface: {@code /grant-access}, {@code /revoke-access}, {@code /access-status} and
 * {@code /settle}.
 * <p>
 * All four are {@link DefaultMemberPermissions#DISABLED}, which hides them from everybody until a
 * guild administrator grants them to a role explicitly. All four write to {@code audit_log}: an
 * admin action nobody can reconstruct afterwards is how "who gave this person access?" becomes
 * unanswerable.
 * </p>
 * <p>
 * Season 1's {@code /test-con}, {@code /manual-con} and {@code /send-contribution-embed} are gone.
 * The first two wrote synthetic contributions with a sentinel payment id, and the third posted an
 * embed whose prices, role ids and image URLs were in the source.
 * </p>
 */
@Slf4j
public final class AdminCommands extends ListenerAdapter {

    /** Discord shows at most 25 autocompletion choices. */
    private static final int MAX_CHOICES = 25;

    private final AccessDirectory access;
    private final AccessRoles roles;
    private final PaymentRequests requests;
    private final AdminLog admin;
    private final Messages messages;
    private final ExecutorService executor;

    public AdminCommands(final AccessDirectory access, final AccessRoles roles,
                         final PaymentRequests requests, final AdminLog admin, final Messages messages,
                         final ExecutorService executor) {
        this.access = access;
        this.roles = roles;
        this.requests = requests;
        this.admin = admin;
        this.messages = messages;
        this.executor = executor;
    }

    /** What the bot registers with Discord on startup. */
    public static List<CommandData> commands() {
        return List.of(
                Commands.slash("grant-access", "Give somebody access without a payment.")
                        .addOption(OptionType.USER, "user", "Who gets it", true)
                        .addOption(OptionType.INTEGER, "days", "How many days, appended to any running access", true)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("revoke-access", "Take away the whole remaining run of somebody's access.")
                        .addOption(OptionType.USER, "user", "Whose access", true)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("access-status", "Show somebody's access, history and open request.")
                        .addOption(OptionType.USER, "user", "Who to look at", true)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash("settle", "Book an open payment request by hand.")
                        .addOptions(new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                OptionType.STRING, "reference", "The NT- reference", true, true))
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED));
    }

    @Override
    public void onSlashCommandInteraction(final @NotNull SlashCommandInteractionEvent event) {
        switch (event.getFullCommandName()) {
            case "grant-access" -> grantAccess(event);
            case "revoke-access" -> revokeAccess(event);
            case "access-status" -> accessStatus(event);
            case "settle" -> settle(event);
            default -> {
            }
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(final @NotNull CommandAutoCompleteInteractionEvent event) {
        if (!"settle".equals(event.getFullCommandName()) || !"reference".equals(event.getFocusedOption().getName())) {
            return;
        }
        final String typed = event.getFocusedOption().getValue().toUpperCase(Locale.ROOT);
        final List<Command.Choice> choices = requests.allOpen().stream()
                .filter(request -> request.reference().contains(typed))
                .limit(MAX_CHOICES)
                .map(request -> new Command.Choice(
                        request.reference() + " - " + request.days() + "d "
                                + Money.format(request.amountCents()),
                        request.reference()))
                .toList();
        event.replyChoices(choices).queue();
    }

    // ---------------------------------------------------------------- commands

    private void grantAccess(final SlashCommandInteractionEvent event) {
        final User user = user(event);
        final int days = (int) option(event, "days").getAsLong();
        if (days <= 0) {
            event.reply("days has to be greater than zero.").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue();
        executor.execute(() -> {
            final AccessGrant grant = access.grantAccess(user.getId(), days, AccessSource.ADMIN, null);
            roles.applyAccessRole(user.getId(), true);
            roles.dm(user.getId(), messages.format(roles.localeOf(user.getId()), "dm.granted.admin",
                    "days", days, "until", AccessRoles.timestamp(grant.validUntil())));

            admin.record("GRANT_ACCESS", event.getUser().getId(), user.getId(), null, days + " days");
            admin.note(event.getUser().getAsMention() + " granted " + user.getAsMention() + " "
                    + days + " days of access, until " + AccessRoles.timestamp(grant.validUntil()) + ".");
            event.getHook().editOriginal("Granted " + days + " days. Access now runs until "
                    + AccessRoles.timestamp(grant.validUntil()) + ".").queue();
        });
    }

    private void revokeAccess(final SlashCommandInteractionEvent event) {
        final User user = user(event);

        event.deferReply(true).queue();
        executor.execute(() -> {
            final int revoked = access.revokeAccess(user.getId());
            roles.applyAccessRole(user.getId(), false);
            if (revoked > 0) {
                roles.dm(user.getId(), messages.get(roles.localeOf(user.getId()), "dm.revoked"));
            }

            admin.record("REVOKE_ACCESS", event.getUser().getId(), user.getId(), null,
                    revoked + " grant(s)");
            admin.note(event.getUser().getAsMention() + " revoked " + user.getAsMention() + "'s access ("
                    + revoked + " grant(s)).");
            event.getHook().editOriginal("Revoked " + revoked + " grant(s).").queue();
        });
    }

    private void accessStatus(final SlashCommandInteractionEvent event) {
        final User user = user(event);

        event.deferReply(true).queue();
        executor.execute(() -> {
            final StringBuilder text = new StringBuilder("**").append(user.getName()).append("**\n");

            final Optional<Instant> until = roles.validUntil(user.getId());
            text.append(until.map(instant -> "Access until " + AccessRoles.timestamp(instant))
                    .orElse("No active access")).append('\n');
            text.append("Donor: ").append(access.isDonor(user.getId()) ? "yes" : "no").append('\n');
            text.append("Language: ").append(roles.localeOf(user.getId()).getLanguage()).append('\n');
            text.append("Linked account: ")
                    .append(access.linkedMinecraftAccount(user.getId()).map(Objects::toString).orElse("none"))
                    .append("\n\n");

            text.append("**Grants**\n");
            final List<AccessGrant> grants = access.grantsOf(user.getId());
            if (grants.isEmpty()) {
                text.append("none\n");
            }
            for (final AccessGrant grant : grants) {
                text.append("- ").append(AccessRoles.timestamp(grant.validFrom()))
                        .append(" to ").append(AccessRoles.timestamp(grant.validUntil()))
                        .append(" (").append(grant.source()).append(')')
                        .append(grant.revoked() == null ? "" : " **revoked**")
                        .append('\n');
            }

            text.append("\n**Recent requests**\n");
            final List<PaymentRequest> recent = requests.recentOf(user.getId(), 5);
            if (recent.isEmpty()) {
                text.append("none\n");
            }
            for (final PaymentRequest request : recent) {
                text.append("- `").append(request.reference()).append("` ")
                        .append(request.days()).append("d ")
                        .append(Money.format(request.amountCents())).append(' ')
                        .append(request.status()).append('\n');
            }

            event.getHook().editOriginal(text.toString()).queue();
        });
    }

    private void settle(final SlashCommandInteractionEvent event) {
        final String reference = option(event, "reference").getAsString().trim().toUpperCase(Locale.ROOT);

        event.deferReply(true).queue();
        executor.execute(() -> {
            final Optional<PaymentRequest> found = requests.byReference(reference);
            if (found.isEmpty()) {
                event.getHook().editOriginal("No request has the reference `" + reference + "`.").queue();
                return;
            }
            final PaymentRequest request = found.get();
            if (!requests.settleManually(request.id())) {
                event.getHook().editOriginal("`" + reference + "` is " + request.status()
                        + ", so there is nothing to book.").queue();
                return;
            }

            // The ordered number of days, not a derived one: there is no amount to derive from,
            // which is exactly why this needed a human in the first place.
            final AccessGrant grant = access.grantAccess(
                    request.discordId(), request.days(), AccessSource.PURCHASE, request.id());
            roles.applyAccessRole(request.discordId(), true);
            if (request.donationRequested()) {
                access.setDonor(request.discordId(), true);
                roles.grantDonorRole(request.discordId());
            }
            roles.dm(request.discordId(), messages.format(roles.localeOf(request.discordId()), "dm.granted",
                    "until", AccessRoles.timestamp(grant.validUntil())));

            admin.record("SETTLE", event.getUser().getId(), request.discordId(), null,
                    "manual, reference=" + reference + " days=" + request.days());
            admin.note(event.getUser().getAsMention() + " settled `" + reference + "` by hand: "
                    + request.days() + " days for <@" + request.discordId() + ">.");
            event.getHook().editOriginal("Booked `" + reference + "`. Access runs until "
                    + AccessRoles.timestamp(grant.validUntil()) + ".").queue();
        });
    }

    // ---------------------------------------------------------------- helpers

    private static User user(final SlashCommandInteractionEvent event) {
        return option(event, "user").getAsUser();
    }

    private static OptionMapping option(final SlashCommandInteractionEvent event, final String name) {
        return Objects.requireNonNull(event.getOption(name), name + " is a required option");
    }
}
