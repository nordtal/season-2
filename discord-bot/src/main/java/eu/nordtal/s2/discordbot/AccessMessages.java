package eu.nordtal.s2.discordbot;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.spec.Arg;
import eu.nordtal.s2.common.message.spec.Key;
import eu.nordtal.s2.common.message.spec.MessageSpec;
import eu.nordtal.s2.common.message.spec.MessageSpecs;
import eu.nordtal.s2.common.message.spec.Name;

/**
 * Every message of the access bundle, one method per key.
 */
@MessageSpec("access")
public interface AccessMessages {

    /** The messages; stateless, so one instance serves every caller. */
    AccessMessages MESSAGES = MessageSpecs.create(AccessMessages.class);

    Contribution contribution();

    @Name("Contribution")
    interface Contribution {

        @Name("Title")
        MessageRef title();

        @Name("Body")
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
        MessageRef button();
    }

    Link link();

    @Name("Link")
    interface Link {

        @Name("Title")
        MessageRef title();

        @Name("Body")
        MessageRef body();

        @Name("Unlink hint")
        MessageRef unlinkHint();

        @Name("Button")
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
        MessageRef choose();

        @Name("Option")
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
        MessageRef donation(@Arg("user") Object user, @Arg("amount") Object amount);
    }

    Register register();

    @Name("Register")
    interface Register {

        @Name("Title")
        MessageRef title();

        @Name("Body")
        MessageRef body();

        @Name("Button")
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
        MessageRef inviteButton();

        Modal modal();

        @Name("Modal")
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
            MessageRef pickerPlaceholder();

            @Name("Pick")
            MessageRef pick();

            @Name("Sent")
            MessageRef sent(@Arg("partner") Object partner);

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
            MessageRef dm(@Arg("team") Object team);

            @Name("Accept")
            MessageRef accept();

            @Name("Decline")
            MessageRef decline();

            @Name("Accepted")
            MessageRef accepted(@Arg("team") Object team);

            @Name("Declined")
            MessageRef declined(@Arg("team") Object team);

            @Name("No longer pending")
            MessageRef noLongerPending();

            @Name("Owner notified accepted")
            MessageRef ownerNotifiedAccepted(@Arg("player") Object player, @Arg("team") Object team);

            @Name("Owner notified declined")
            MessageRef ownerNotifiedDeclined(@Arg("player") Object player, @Arg("team") Object team);
        }
    }

    Status status();

    @Name("Status")
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
