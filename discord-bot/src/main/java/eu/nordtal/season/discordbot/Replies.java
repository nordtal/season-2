package eu.nordtal.season.discordbot;

import eu.nordtal.season.messages.spec.layout.Layout;
import eu.nordtal.season.messages.spec.layout.LayoutSchema;
import eu.nordtal.season.messages.spec.layout.Line;
import eu.nordtal.season.messages.spec.layout.Part;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class Replies {

    static final Layout PURCHASE_PENDING = reply("purchase-pending", "Purchase", "purchase.link.pending");
    static final Layout PURCHASE_REFUSED = reply("purchase-refused", "Purchase", "purchase.link.refused");
    static final Layout PURCHASE_SLOW = reply("purchase-slow", "Purchase", "purchase.link.slow");
    static final Layout PURCHASE_GONE = reply("purchase-gone", "Purchase", "purchase.gone");
    static final Layout PURCHASE_TIER_GONE = reply("purchase-tier-gone", "Purchase", "purchase.tier-gone");
    static final Layout PURCHASE_FAILED = reply("purchase-failed", "Purchase", "purchase.failed");

    static final Layout LINK_SUCCESS = reply("link-success", "Link", "link.success");
    static final Layout LINK_INVALID_CODE = reply("link-invalid-code", "Link", "link.invalid-code");
    static final Layout LINK_ALREADY_LINKED = reply("link-already-linked", "Link", "link.already-linked");
    static final Layout LINK_FAILED = reply("link-failed", "Link", "link.failed");
    static final Layout LINK_TOO_MANY = reply("link-too-many", "Link", "link.too-many");
    static final Layout UNLINK_SUCCESS = reply("unlink-success", "Link", "unlink.success");
    static final Layout UNLINK_NONE = reply("unlink-none", "Link", "unlink.none");

    static final Layout ONBOARDING_SAVED = reply("onboarding-saved", "Onboarding", "onboarding.saved");
    static final Layout ONBOARDING_FAILED = reply("onboarding-failed", "Onboarding", "onboarding.failed");

    static final Layout REGISTER_INVALID_NAME = reply("register-invalid-name", "Register", "register.invalid-name");
    static final Layout REGISTER_NAME_TAKEN = reply("register-name-taken", "Register", "register.name-taken");
    static final Layout REGISTER_ALREADY =
            reply("register-already-registered", "Register", "register.already-registered");
    static final Layout REGISTER_CLOSED = reply("register-closed", "Register", "register.closed");
    static final Layout REGISTER_FAILED = reply("register-failed", "Register", "register.failed");
    static final Layout INVITE_SENT = reply("invite-sent", "Register", "register.invite.sent");
    static final Layout INVITE_NOT_OWNER = reply("invite-not-owner", "Register", "register.invite.not-owner");
    static final Layout INVITE_TEAM_FULL = reply("invite-team-full", "Register", "register.invite.team-full");
    static final Layout INVITE_PENDING = reply("invite-pending", "Register", "register.invite.pending");
    static final Layout INVITE_SELF = reply("invite-self", "Register", "register.invite.cannot-invite-self");
    static final Layout INVITE_UNAVAILABLE =
            reply("invite-unavailable", "Register", "register.invite.target-unavailable");
    static final Layout INVITE_ACCEPTED = reply("invite-accepted", "Register", "register.invite.accepted");
    static final Layout INVITE_DECLINED = reply("invite-declined", "Register", "register.invite.declined");
    static final Layout INVITE_NO_LONGER_PENDING =
            reply("invite-no-longer-pending", "Register", "register.invite.no-longer-pending");
    static final Layout INVITE_OWNER_ACCEPTED =
            reply("invite-owner-accepted", "Register", "register.invite.owner-notified-accepted");
    static final Layout INVITE_OWNER_DECLINED =
            reply("invite-owner-declined", "Register", "register.invite.owner-notified-declined");

    static final Layout DM_GRANTED = reply("dm-granted", "Direct messages", "dm.granted");
    static final Layout DM_GRANTED_SHORT = reply("dm-granted-short", "Direct messages", "dm.granted.short");
    static final Layout DM_GRANTED_ADMIN = reply("dm-granted-admin", "Direct messages", "dm.granted.admin");
    static final Layout DM_DONOR = reply("dm-donor", "Direct messages", "dm.donor");
    static final Layout DM_EXPIRING = reply("dm-expiring", "Direct messages", "dm.expiring");
    static final Layout DM_EXPIRED = reply("dm-expired", "Direct messages", "dm.expired");
    static final Layout DM_REVOKED = reply("dm-revoked", "Direct messages", "dm.revoked");

    static final Layout PUBLIC_DONATION = reply("public-donation", "Contribution", "public.donation");

    private static final Map<String, Layout> BY_KEY = LayoutSchema.declared(Replies.class).stream()
            .collect(Collectors.toUnmodifiableMap(
                    layout -> layout.keys().getFirst().substring("access/".length()), Function.identity()));

    private Replies() {}

    /**
     * Returns the layout of the reply whose one text is {@code key}.
     *
     * @throws IllegalArgumentException if no reply shows that text
     */
    static Layout of(final String key) {
        final Layout layout = BY_KEY.get(key);
        if (layout == null) {
            throw new IllegalArgumentException("no reply layout shows " + key);
        }
        return layout;
    }

    private static Layout reply(final String name, final String area, final String key) {
        return Layout.discord("access", name, area, List.of(), Part.text(Line.text("access/" + key)));
    }
}
