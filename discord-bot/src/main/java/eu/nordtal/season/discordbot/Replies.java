package eu.nordtal.season.discordbot;

import eu.nordtal.season.messages.context.DiscordMemberContext;
import eu.nordtal.season.messages.context.TeamContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.Money;
import java.time.Instant;

/** The replies and direct messages: one text block each, filled with nothing but its values. */
public final class Replies {

    public record Refused(boolean passes) {}

    public record Reference(@Example("NT-A1B2C3") String reference) {}

    public record Saved(
            @Example("English") String language,
            @Example("Central Europe") String region) {}

    public record Partner(DiscordMemberContext partner) {}

    public record Team(TeamContext team) {}

    public record Answer(DiscordMemberContext player, TeamContext team) {}

    public record Until(@Example("2026-11-22T18:00:00Z") Instant until) {}

    public record Paid(
            @Example("500") Money paid,
            @Example("30") long days,
            @Example("2026-11-22T18:00:00Z") Instant until) {}

    public record Given(
            @Example("30") long days,
            @Example("2026-11-22T18:00:00Z") Instant until) {}

    /** @param channel the contribution channel, empty where the language has none */
    public record Reminder(
            @Example("2026-11-22T18:00:00Z") Instant until,
            @Example("#contribution") String channel) {}

    /** @param channel the contribution channel, empty where the language has none */
    public record Ended(@Example("#contribution") String channel) {}

    public record Donated(
            DiscordMemberContext user, @Example("500") Money amount) {}

    public static final DocumentSpec<DocumentSpec.None> PURCHASE_PENDING =
            reply("purchase-pending", "Purchase").titled("Payment link being created");
    public static final DocumentSpec<Refused> PURCHASE_REFUSED =
            reply("purchase-refused", "Purchase", Refused.class).titled("Payment link refused");
    public static final DocumentSpec<Reference> PURCHASE_SLOW =
            reply("purchase-slow", "Purchase", Reference.class).titled("Payment link late");
    public static final DocumentSpec<DocumentSpec.None> PURCHASE_GONE =
            reply("purchase-gone", "Purchase").titled("Purchase closed");
    public static final DocumentSpec<DocumentSpec.None> PURCHASE_TIER_GONE =
            reply("purchase-tier-gone", "Purchase").titled("Period no longer offered");
    public static final DocumentSpec<DocumentSpec.None> PURCHASE_FAILED =
            reply("purchase-failed", "Purchase").titled("Payment link failed");

    public static final DocumentSpec<DocumentSpec.None> LINK_SUCCESS =
            reply("link-success", "Link").titled("Account linked");
    public static final DocumentSpec<DocumentSpec.None> LINK_INVALID_CODE =
            reply("link-invalid-code", "Link").titled("Wrong or expired code");
    public static final DocumentSpec<DocumentSpec.None> LINK_ALREADY_LINKED =
            reply("link-already-linked", "Link").titled("Account already linked");
    public static final DocumentSpec<DocumentSpec.None> LINK_FAILED =
            reply("link-failed", "Link").titled("Linking failed");
    public static final DocumentSpec<DocumentSpec.None> LINK_TOO_MANY =
            reply("link-too-many", "Link").titled("Too many wrong codes");
    public static final DocumentSpec<DocumentSpec.None> UNLINK_SUCCESS =
            reply("unlink-success", "Link").titled("Account unlinked");
    public static final DocumentSpec<DocumentSpec.None> UNLINK_NONE =
            reply("unlink-none", "Link").titled("Nothing to unlink");

    public static final DocumentSpec<Saved> ONBOARDING_SAVED =
            reply("onboarding-saved", "Onboarding", Saved.class).titled("Language and region saved");
    public static final DocumentSpec<DocumentSpec.None> ONBOARDING_FAILED =
            reply("onboarding-failed", "Onboarding").titled("Saving failed");

    public static final DocumentSpec<DocumentSpec.None> REGISTER_INVALID_NAME =
            reply("register-invalid-name", "Register").titled("Team name too short or too long");
    public static final DocumentSpec<DocumentSpec.None> REGISTER_NAME_TAKEN =
            reply("register-name-taken", "Register").titled("Team name taken");
    public static final DocumentSpec<DocumentSpec.None> REGISTER_ALREADY =
            reply("register-already-registered", "Register").titled("Already registered");
    public static final DocumentSpec<DocumentSpec.None> REGISTER_CLOSED =
            reply("register-closed", "Register").titled("Registration closed");
    public static final DocumentSpec<DocumentSpec.None> REGISTER_FAILED =
            reply("register-failed", "Register").titled("Registration failed");
    public static final DocumentSpec<Partner> INVITE_SENT =
            reply("invite-sent", "Register", Partner.class).titled("Invitation sent");
    public static final DocumentSpec<DocumentSpec.None> INVITE_NOT_OWNER =
            reply("invite-not-owner", "Register").titled("Invitation without a team");
    public static final DocumentSpec<DocumentSpec.None> INVITE_TEAM_FULL =
            reply("invite-team-full", "Register").titled("Team already full");
    public static final DocumentSpec<DocumentSpec.None> INVITE_PENDING =
            reply("invite-pending", "Register").titled("Invitation still open");
    public static final DocumentSpec<DocumentSpec.None> INVITE_SELF =
            reply("invite-self", "Register").titled("Invitation to oneself");
    public static final DocumentSpec<DocumentSpec.None> INVITE_UNAVAILABLE =
            reply("invite-unavailable", "Register").titled("Account cannot be invited");
    public static final DocumentSpec<Team> INVITE_ACCEPTED =
            reply("invite-accepted", "Register", Team.class).titled("Invitation accepted, to the partner");
    public static final DocumentSpec<Team> INVITE_DECLINED =
            reply("invite-declined", "Register", Team.class).titled("Invitation declined, to the partner");
    public static final DocumentSpec<DocumentSpec.None> INVITE_NO_LONGER_PENDING =
            reply("invite-no-longer-pending", "Register").titled("Invitation closed");
    public static final DocumentSpec<Answer> INVITE_OWNER_ACCEPTED =
            reply("invite-owner-accepted", "Register", Answer.class).titled("Invitation accepted, to the team owner");
    public static final DocumentSpec<Answer> INVITE_OWNER_DECLINED =
            reply("invite-owner-declined", "Register", Answer.class).titled("Invitation declined, to the team owner");

    public static final DocumentSpec<Until> DM_GRANTED =
            reply("dm-granted", "Direct messages", Until.class).titled("Access paid");
    public static final DocumentSpec<Paid> DM_GRANTED_SHORT =
            reply("dm-granted-short", "Direct messages", Paid.class).titled("Access paid, with the amount");
    public static final DocumentSpec<Given> DM_GRANTED_ADMIN =
            reply("dm-granted-admin", "Direct messages", Given.class).titled("Access given by an admin");
    public static final DocumentSpec<DocumentSpec.None> DM_DONOR =
            reply("dm-donor", "Direct messages").titled("Donor role kept");
    public static final DocumentSpec<Reminder> DM_EXPIRING =
            reply("dm-expiring", "Direct messages", Reminder.class).titled("Access ends soon");
    public static final DocumentSpec<Ended> DM_EXPIRED =
            reply("dm-expired", "Direct messages", Ended.class).titled("Access ended");
    public static final DocumentSpec<DocumentSpec.None> DM_REVOKED =
            reply("dm-revoked", "Direct messages").titled("Access revoked");

    public static final DocumentSpec<Donated> PUBLIC_DONATION =
            reply("public-donation", "Contribution", Donated.class).titled("Donation thanks in the channel");

    private Replies() {}

    private static DocumentSpec<DocumentSpec.None> reply(final String name, final String area) {
        return DocumentSpec.message(StoredDocuments.NAMESPACE, name, area);
    }

    private static <V extends Record> DocumentSpec<V> reply(
            final String name, final String area, final Class<V> values) {
        return DocumentSpec.message(StoredDocuments.NAMESPACE, name, area, values);
    }
}
