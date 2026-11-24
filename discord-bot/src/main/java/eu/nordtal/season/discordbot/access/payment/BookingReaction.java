package eu.nordtal.season.discordbot.access.payment;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.payment.BookedPayment;
import eu.nordtal.season.discordbot.AdminLog;
import eu.nordtal.season.discordbot.Replies;
import eu.nordtal.season.discordbot.access.discord.AccessRoles;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.value.Money;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class BookingReaction {

    private final AccessRoles roles;
    private final AdminLog admin;

    public BookingReaction(final AccessRoles roles, final AdminLog admin) {
        this.roles = roles;
        this.admin = admin;
    }

    public void tell(final BookedPayment booked) {
        final DiscordId payer = booked.person();
        roles.applyAccessRole(payer, true);
        if (booked.donation()) {
            roles.grantDonorRole(payer);
        }

        roles.dm(
                payer,
                booked.downgraded()
                        ? new Sent<>(
                                Replies.DM_GRANTED_SHORT,
                                new Replies.Paid(
                                        Money.euroCents(Objects.requireNonNull(
                                                booked.receivedCents(), "a short payment has an amount")),
                                        booked.days(),
                                        booked.until()))
                        : new Sent<>(Replies.DM_GRANTED, new Replies.Until(booked.until())));
        if (booked.donation()) {
            roles.dm(payer, Sent.of(Replies.DM_DONOR));
        }

        admin.show(booked.entry());
        log.info(
                "access confirmation notification queued: member={} reference={} days={}",
                payer,
                booked.reference(),
                booked.days());
    }
}
