package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.context.DiscordMemberContext;
import eu.nordtal.s2.common.message.context.PlayerContext;
import eu.nordtal.s2.common.message.spec.Arg;
import eu.nordtal.s2.common.message.spec.Key;
import eu.nordtal.s2.common.message.spec.Name;

/** Donor access: a player's own status, and the admin commands that grant, revoke and settle it. */
@Name("Access")
public interface Access {

    @Name("Header")
    MessageRef header(@Arg("player") PlayerContext player, @Arg("discord") DiscordMemberContext discord);

    @Name("Until")
    MessageRef until(@Arg("until") Object until);

    @Name("None")
    MessageRef none();

    @Name("Donor")
    MessageRef donor(@Arg("donor") Object donor);

    @Name("Yes")
    MessageRef yes();

    @Name("No")
    MessageRef no();

    @Name("Language")
    MessageRef language(@Arg("language") Object language);

    @Name("Linked")
    MessageRef linked(@Arg("account") Object account);

    @Name("None linked")
    MessageRef noneLinked();

    @Name("Not linked")
    MessageRef notLinked();

    @Name("No such member")
    MessageRef noSuchMember(@Arg("discord") DiscordMemberContext discord);

    @Name("Failed")
    MessageRef failed();

    @Name("Granted")
    MessageRef granted(@Arg("days") Object days, @Arg("until") Object until);

    @Name("Revoked")
    MessageRef revoked(@Arg("count") Object count);

    @Name("Unlinked")
    MessageRef unlinked(@Arg("member") DiscordMemberContext member);

    Grants grants();

    @Name("Grants")
    interface Grants {

        @Name("Header")
        MessageRef header();

        @Name("None")
        MessageRef none();

        @Name("Line")
        MessageRef line(@Arg("from") Object from, @Arg("until") Object until, @Arg("source") Object source);

        @Name("Revoked")
        MessageRef revoked(@Arg("from") Object from, @Arg("until") Object until, @Arg("source") Object source);
    }

    Purchases purchases();

    @Name("Purchases")
    interface Purchases {

        @Name("Header")
        MessageRef header();

        @Name("None")
        MessageRef none();

        @Name("Line")
        MessageRef line(
                @Arg("reference") Object reference,
                @Arg("days") Object days,
                @Arg("amount") Object amount,
                @Arg("status") Object status);
    }

    @Key("revoked")
    Revoked revokedSection();

    @Name("Revoked")
    interface Revoked {

        @Name("One")
        MessageRef one();

        @Name("None")
        MessageRef none();
    }

    Settle settle();

    @Name("Settle")
    interface Settle {

        @Name("Unknown")
        MessageRef unknown(@Arg("reference") Object reference);

        @Name("Not open")
        MessageRef notOpen(@Arg("reference") Object reference, @Arg("status") Object status);

        @Name("Booked")
        MessageRef booked(@Arg("reference") Object reference, @Arg("days") Object days, @Arg("until") Object until);
    }

    Messages messages();

    @Name("Messages")
    interface Messages {

        @Name("Reloaded")
        MessageRef reloaded();

        @Name("Reloaded with unknown")
        MessageRef reloadedWithUnknown(@Arg("keys") Object keys);

        @Name("Reload failed")
        MessageRef reloadFailed();
    }
}
