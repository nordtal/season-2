package eu.nordtal.s2.discordbot;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.context.DiscordMemberContext;
import eu.nordtal.s2.common.message.context.TeamContext;
import eu.nordtal.s2.common.message.spec.Arg;
import eu.nordtal.s2.common.message.spec.Display;
import eu.nordtal.s2.common.message.spec.Format;
import eu.nordtal.s2.common.message.spec.Key;
import eu.nordtal.s2.common.message.spec.MessageSpec;
import eu.nordtal.s2.common.message.spec.MessageSpecs;
import eu.nordtal.s2.common.message.spec.Name;
import eu.nordtal.s2.common.message.spec.Shown;
import eu.nordtal.s2.common.message.spec.TextFormat;

/**
 * Every message of the access bundle, one method per key.
 */
@MessageSpec("access")
@Shown(Display.DISCORD_MESSAGE)
@Format(TextFormat.DISCORD_MARKDOWN)
public interface AccessMessages {

    /** The messages; stateless, so one instance serves every caller. */
    AccessMessages MESSAGES = MessageSpecs.create(AccessMessages.class);

    Contribution contribution();

    @Name("Contribution")
    interface Contribution {

        @Name("Title")
        @Shown(Display.DISCORD_EMBED)
        MessageRef title();

        @Name("Body")
        @Shown(Display.DISCORD_EMBED)
        MessageRef body();

        @Name("Prices")
        MessageRef prices();

        @Name("Tier line")
        MessageRef tierLine(@Arg("days") Object days, @Arg("price") Object price);

        @Name("Donation")
        MessageRef donation(@Arg("amount") Object amount);

        @Name("Renew")
        MessageRef renew();

        @Name("Button")
        @Shown(Display.DISCORD_BUTTON)
        MessageRef button();
    }

    Link link();

    @Name("Link")
    interface Link {

        @Name("Title")
        @Shown(Display.DISCORD_EMBED)
        MessageRef title();

        @Name("Body")
        @Shown(Display.DISCORD_EMBED)
        MessageRef body();

        @Name("Unlink hint")
        MessageRef unlinkHint();

        @Name("Button")
        @Shown(Display.DISCORD_BUTTON)
        MessageRef button();

        @Name("Success")
        MessageRef success();

        @Name("Invalid code")
        MessageRef invalidCode();

        @Name("Already linked")
        MessageRef alreadyLinked();

        @Name("Failed")
        MessageRef failed();

        @Name("Too many")
        MessageRef tooMany();

        Modal modal();

        @Name("Modal")
        @Shown(Display.DISCORD_MODAL)
        interface Modal {

            @Name("Title")
            MessageRef title();

            @Name("Code label")
            MessageRef codeLabel();

            @Name("Code placeholder")
            MessageRef codePlaceholder();
        }
    }

    Unlink unlink();

    @Name("Unlink")
    interface Unlink {

        @Name("Success")
        MessageRef success();

        @Name("None")
        MessageRef none();
    }

    Purchase purchase();

    @Name("Purchase")
    interface Purchase {

        @Name("Choose")
        @Shown(Display.DISCORD_SELECT)
        MessageRef choose();

        @Name("Option")
        @Shown(Display.DISCORD_SELECT)
        MessageRef option(@Arg("days") Object days, @Arg("price") Object price);

        @Name("Summary")
        MessageRef summary(@Arg("days") Object days, @Arg("price") Object price);

        @Name("Link")
        MessageRef link(@Arg("total") Object total, @Arg("url") Object url);

        @Name("Gone")
        MessageRef gone();

        @Name("Tier gone")
        MessageRef tierGone();

        @Name("Failed")
        MessageRef failed();

        @Key("summary")
        Summary summarySection();

        @Name("Summary")
        interface Summary {

            @Name("Donation")
            MessageRef donation(@Arg("donation") Object donation);

            @Name("Total")
            MessageRef total(@Arg("total") Object total);
        }

        Button button();

        @Name("Button")
        @Shown(Display.DISCORD_BUTTON)
        interface Button {

            @Name("Confirm")
            MessageRef confirm();

            @Name("Change")
            MessageRef change();

            Donation donation();

            @Name("Donation")
            interface Donation {

                @Name("Add")
                MessageRef add(@Arg("amount") Object amount);

                @Name("Remove")
                MessageRef remove();
            }
        }

        @Key("link")
        Link linkSection();

        @Name("Link")
        interface Link {

            @Name("Reference")
            MessageRef reference(@Arg("reference") Object reference);

            @Name("Expiry")
            MessageRef ttl(@Arg("hours") Object hours);

            @Name("Pending")
            MessageRef pending();

            @Name("Refused")
            MessageRef refused();

            @Name("Slow")
            MessageRef slow(@Arg("reference") Object reference);
        }
    }

    Dm dm();

    @Name("Direct message")
    interface Dm {

        @Name("Granted")
        MessageRef granted(@Arg("until") Object until);

        @Name("Donor")
        MessageRef donor();

        @Name("Expiring")
        MessageRef expiring(@Arg("until") Object until, @Arg("days") Object days);

        @Name("Expired")
        MessageRef expired();

        @Name("Revoked")
        MessageRef revoked();

        @Key("granted")
        Granted grantedSection();

        @Name("Granted")
        interface Granted {

            @Name("Short")
            @Key("short")
            MessageRef shortMessage(@Arg("paid") Object paid, @Arg("days") Object days, @Arg("until") Object until);

            @Name("Admin")
            MessageRef admin(@Arg("days") Object days, @Arg("until") Object until);
        }
    }

    @Key("public")
    Public publicSection();

    @Name("Public")
    interface Public {

        @Name("Donation")
        MessageRef donation(@Arg("user") DiscordMemberContext user, @Arg("amount") Object amount);
    }

    Register register();

    @Name("Register")
    interface Register {

        @Name("Title")
        @Shown(Display.DISCORD_EMBED)
        MessageRef title();

        @Name("Body")
        @Shown(Display.DISCORD_EMBED)
        MessageRef body();

        @Name("Button")
        @Shown(Display.DISCORD_BUTTON)
        MessageRef button();

        @Name("Success")
        MessageRef success(@Arg("name") Object name);

        @Name("Invalid name")
        MessageRef invalidName();

        @Name("Name taken")
        MessageRef nameTaken();

        @Name("Already registered")
        MessageRef alreadyRegistered();

        @Name("Failed")
        MessageRef failed();

        @Name("Invite button")
        @Shown(Display.DISCORD_BUTTON)
        MessageRef inviteButton();

        Modal modal();

        @Name("Modal")
        @Shown(Display.DISCORD_MODAL)
        interface Modal {

            @Name("Title")
            MessageRef title();

            @Name("Name label")
            MessageRef nameLabel();

            @Name("Name placeholder")
            MessageRef namePlaceholder();
        }

        Invite invite();

        @Name("Invite")
        interface Invite {

            @Name("Picker placeholder")
            @Shown(Display.DISCORD_SELECT)
            MessageRef pickerPlaceholder();

            @Name("Pick")
            @Shown(Display.DISCORD_SELECT)
            MessageRef pick();

            @Name("Sent")
            MessageRef sent(@Arg("partner") DiscordMemberContext partner);

            @Name("Not owner")
            MessageRef notOwner();

            @Name("Team full")
            MessageRef teamFull();

            @Name("Pending")
            MessageRef pending();

            @Name("Cannot invite self")
            MessageRef cannotInviteSelf();

            @Name("Target unavailable")
            MessageRef targetUnavailable();

            @Name("Direct message")
            MessageRef dm(@Arg("team") TeamContext team);

            @Name("Accept")
            @Shown(Display.DISCORD_BUTTON)
            MessageRef accept();

            @Name("Decline")
            @Shown(Display.DISCORD_BUTTON)
            MessageRef decline();

            @Name("Accepted")
            MessageRef accepted(@Arg("team") TeamContext team);

            @Name("Declined")
            MessageRef declined(@Arg("team") TeamContext team);

            @Name("No longer pending")
            MessageRef noLongerPending();

            @Name("Owner notified accepted")
            MessageRef ownerNotifiedAccepted(@Arg("player") DiscordMemberContext player, @Arg("team") TeamContext team);

            @Name("Owner notified declined")
            MessageRef ownerNotifiedDeclined(@Arg("player") DiscordMemberContext player, @Arg("team") TeamContext team);
        }
    }

    Status status();

    @Name("Status")
    @Shown(Display.DISCORD_CHANNEL)
    @Format(TextFormat.PLAIN)
    interface Status {

        @Name("Pre event")
        MessageRef preEvent(@Arg("teams") Object teams);

        @Name("Start event")
        MessageRef startEvent(@Arg("teams") Object teams, @Arg("players") Object players);

        @Name("SMP")
        MessageRef smp(@Arg("players") Object players);

        @Name("Maintenance")
        MessageRef maintenance();

        PreLaunch preLaunch();

        @Name("Pre launch")
        interface PreLaunch {

            @Name("Days")
            MessageRef days(@Arg("days") Object days, @Arg("hours") Object hours);

            @Name("Hours")
            MessageRef hours(@Arg("hours") Object hours);

            @Name("Minutes")
            MessageRef minutes(@Arg("minutes") Object minutes);

            @Name("Imminent")
            MessageRef imminent();

            @Name("Unknown")
            MessageRef unknown();
        }
    }
}
