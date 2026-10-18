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

    public record Until(Instant until) {}

    public record Paid(Money paid, @Example("30") long days, Instant until) {}

    public record Given(@Example("30") long days, Instant until) {}

    /** @param channel the contribution channel, empty where the language has none */
    public record Reminder(
            Instant until, @Example("#contribution") String channel) {}

    /** @param channel the contribution channel, empty where the language has none */
    public record Ended(@Example("#contribution") String channel) {}

    public record Donated(DiscordMemberContext user, Money amount) {}

    public static final DocumentSpec<DocumentSpec.None> PURCHASE_PENDING = reply("purchase-pending", "Purchase");
    public static final DocumentSpec<Refused> PURCHASE_REFUSED = reply("purchase-refused", "Purchase", Refused.class);
    public static final DocumentSpec<Reference> PURCHASE_SLOW = reply("purchase-slow", "Purchase", Reference.class);
    public static final DocumentSpec<DocumentSpec.None> PURCHASE_GONE = reply("purchase-gone", "Purchase");
    public static final DocumentSpec<DocumentSpec.None> PURCHASE_TIER_GONE = reply("purchase-tier-gone", "Purchase");
    public static final DocumentSpec<DocumentSpec.None> PURCHASE_FAILED = reply("purchase-failed", "Purchase");

    public static final DocumentSpec<DocumentSpec.None> LINK_SUCCESS = reply("link-success", "Link");
    public static final DocumentSpec<DocumentSpec.None> LINK_INVALID_CODE = reply("link-invalid-code", "Link");
    public static final DocumentSpec<DocumentSpec.None> LINK_ALREADY_LINKED = reply("link-already-linked", "Link");
    public static final DocumentSpec<DocumentSpec.None> LINK_FAILED = reply("link-failed", "Link");
    public static final DocumentSpec<DocumentSpec.None> LINK_TOO_MANY = reply("link-too-many", "Link");
    public static final DocumentSpec<DocumentSpec.None> UNLINK_SUCCESS = reply("unlink-success", "Link");
    public static final DocumentSpec<DocumentSpec.None> UNLINK_NONE = reply("unlink-none", "Link");

    public static final DocumentSpec<Saved> ONBOARDING_SAVED = reply("onboarding-saved", "Onboarding", Saved.class);
    public static final DocumentSpec<DocumentSpec.None> ONBOARDING_FAILED = reply("onboarding-failed", "Onboarding");

    public static final DocumentSpec<DocumentSpec.None> REGISTER_INVALID_NAME =
            reply("register-invalid-name", "Register");
    public static final DocumentSpec<DocumentSpec.None> REGISTER_NAME_TAKEN = reply("register-name-taken", "Register");
    public static final DocumentSpec<DocumentSpec.None> REGISTER_ALREADY =
            reply("register-already-registered", "Register");
    public static final DocumentSpec<DocumentSpec.None> REGISTER_CLOSED = reply("register-closed", "Register");
    public static final DocumentSpec<DocumentSpec.None> REGISTER_FAILED = reply("register-failed", "Register");
    public static final DocumentSpec<Partner> INVITE_SENT = reply("invite-sent", "Register", Partner.class);
    public static final DocumentSpec<DocumentSpec.None> INVITE_NOT_OWNER = reply("invite-not-owner", "Register");
    public static final DocumentSpec<DocumentSpec.None> INVITE_TEAM_FULL = reply("invite-team-full", "Register");
    public static final DocumentSpec<DocumentSpec.None> INVITE_PENDING = reply("invite-pending", "Register");
    public static final DocumentSpec<DocumentSpec.None> INVITE_SELF = reply("invite-self", "Register");
    public static final DocumentSpec<DocumentSpec.None> INVITE_UNAVAILABLE = reply("invite-unavailable", "Register");
    public static final DocumentSpec<Team> INVITE_ACCEPTED = reply("invite-accepted", "Register", Team.class);
    public static final DocumentSpec<Team> INVITE_DECLINED = reply("invite-declined", "Register", Team.class);
    public static final DocumentSpec<DocumentSpec.None> INVITE_NO_LONGER_PENDING =
            reply("invite-no-longer-pending", "Register");
    public static final DocumentSpec<Answer> INVITE_OWNER_ACCEPTED =
            reply("invite-owner-accepted", "Register", Answer.class);
    public static final DocumentSpec<Answer> INVITE_OWNER_DECLINED =
            reply("invite-owner-declined", "Register", Answer.class);

    public static final DocumentSpec<Until> DM_GRANTED = reply("dm-granted", "Direct messages", Until.class);
    public static final DocumentSpec<Paid> DM_GRANTED_SHORT = reply("dm-granted-short", "Direct messages", Paid.class);
    public static final DocumentSpec<Given> DM_GRANTED_ADMIN =
            reply("dm-granted-admin", "Direct messages", Given.class);
    public static final DocumentSpec<DocumentSpec.None> DM_DONOR = reply("dm-donor", "Direct messages");
    public static final DocumentSpec<Reminder> DM_EXPIRING = reply("dm-expiring", "Direct messages", Reminder.class);
    public static final DocumentSpec<Ended> DM_EXPIRED = reply("dm-expired", "Direct messages", Ended.class);
    public static final DocumentSpec<DocumentSpec.None> DM_REVOKED = reply("dm-revoked", "Direct messages");

    public static final DocumentSpec<Donated> PUBLIC_DONATION = reply("public-donation", "Contribution", Donated.class);

    private Replies() {}

    private static DocumentSpec<DocumentSpec.None> reply(final String name, final String area) {
        return DocumentSpec.message(StoredDocuments.NAMESPACE, name, area);
    }

    private static <V extends Record> DocumentSpec<V> reply(
            final String name, final String area, final Class<V> values) {
        return DocumentSpec.message(StoredDocuments.NAMESPACE, name, area, values);
    }
}
