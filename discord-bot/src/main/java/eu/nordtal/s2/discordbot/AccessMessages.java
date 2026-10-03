package eu.nordtal.s2.discordbot;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.context.DiscordMemberContext;
import eu.nordtal.s2.messages.context.TeamContext;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Display;
import eu.nordtal.s2.messages.spec.Format;
import eu.nordtal.s2.messages.spec.Key;
import eu.nordtal.s2.messages.spec.MessageSpec;
import eu.nordtal.s2.messages.spec.MessageSpecs;
import eu.nordtal.s2.messages.spec.Name;
import eu.nordtal.s2.messages.spec.Shown;
import eu.nordtal.s2.messages.spec.TextFormat;
import eu.nordtal.s2.messages.value.Example;
import eu.nordtal.s2.messages.value.Money;
import java.time.Duration;
import java.time.Instant;

/** Every message of the access bundle, one method per key. */
@MessageSpec("access")
@Shown(Display.DISCORD_MESSAGE)
@Format(TextFormat.DISCORD_MARKDOWN)
public interface AccessMessages {

    AccessMessages MESSAGES = MessageSpecs.create(AccessMessages.class);

    Contribution contribution();

    @Name("Contribution")
    interface Contribution {

        @Name("Title")
        @Shown(Display.DISCORD_EMBED)
        MessageRef title();

        @Name("Prices")
        @Shown(Display.DISCORD_EMBED)
        MessageRef prices();

        @Name("Tier line")
        @Shown(Display.DISCORD_EMBED)
        MessageRef tierLine(@Arg("days") long days, @Arg("price") Money price);

        @Name("Donation heading")
        @Shown(Display.DISCORD_EMBED)
        MessageRef donationHeading();

        @Name("Donation")
        @Shown(Display.DISCORD_EMBED)
        MessageRef donation(@Arg("amount") Money amount);

        @Name("Renew heading")
        @Shown(Display.DISCORD_EMBED)
        MessageRef renewHeading();

        @Name("Renew")
        @Shown(Display.DISCORD_EMBED)
        MessageRef renew();

        @Name("Button")
        @Shown(Display.DISCORD_BUTTON)
        @Format(TextFormat.PLAIN)
        MessageRef button();
    }

    AccessMessages.Link link();

    @Name("Link")
    interface Link {

        @Name("Title")
        @Shown(Display.DISCORD_EMBED)
        MessageRef title();

        @Name("Steps heading")
        @Shown(Display.DISCORD_EMBED)
        MessageRef stepsHeading();

        @Name("Steps")
        @Shown(Display.DISCORD_EMBED)
        MessageRef steps(@Arg("button") @Example("Enter my code") String button);

        @Name("Switch heading")
        @Shown(Display.DISCORD_EMBED)
        MessageRef switchHeading();

        @Name("Switch")
        @Shown(Display.DISCORD_EMBED)
        MessageRef switchAccount();

        @Name("Button")
        @Shown(Display.DISCORD_BUTTON)
        @Format(TextFormat.PLAIN)
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

        Link.Modal modal();

        @Name("Modal")
        @Shown(Display.DISCORD_MODAL)
        @Format(TextFormat.PLAIN)
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
        @Format(TextFormat.PLAIN)
        MessageRef choose();

        @Name("Option")
        @Shown(Display.DISCORD_SELECT)
        @Format(TextFormat.PLAIN)
        MessageRef option(@Arg("days") long days, @Arg("price") Money price);

        @Name("Summary")
        MessageRef summary(@Arg("days") long days, @Arg("price") Money price);

        @Name("Link")
        MessageRef link(@Arg("total") Money total, @Arg("url") @Example("https://bunq.me/t/example") String url);

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
            MessageRef donation(@Arg("donation") Money donation);

            @Name("Total")
            MessageRef total(@Arg("total") Money total);
        }

        Button button();

        @Name("Button")
        @Shown(Display.DISCORD_BUTTON)
        @Format(TextFormat.PLAIN)
        interface Button {

            @Name("Confirm")
            MessageRef confirm();

            @Name("Change")
            MessageRef change();

            Donation donation();

            @Name("Donation")
            interface Donation {

                @Name("Add")
                MessageRef add(@Arg("amount") Money amount);

                @Name("Remove")
                MessageRef remove();
            }
        }

        @Key("link")
        Purchase.Link linkSection();

        @Name("Link")
        interface Link {

            @Name("Reference")
            MessageRef reference(@Arg("reference") @Example("NT-A1B2C3") String reference);

            @Name("Expiry")
            MessageRef ttl(@Arg("until") Instant until);

            @Name("Pending")
            MessageRef pending();

            @Name("Refused")
            MessageRef refused();

            @Name("Slow")
            MessageRef slow(@Arg("reference") @Example("NT-A1B2C3") String reference);
        }
    }

    Dm dm();

    @Name("Direct message")
    interface Dm {

        @Name("Granted")
        MessageRef granted(@Arg("until") Instant until);

        @Name("Donor")
        MessageRef donor();

        @Name("Expiring")
        MessageRef expiring(@Arg("until") Instant until, @Arg("channel") @Example("#contribution") String channel);

        @Name("Expired")
        MessageRef expired(@Arg("channel") @Example("#contribution") String channel);

        @Name("Contribution channel")
        MessageRef channel();

        @Name("Revoked")
        MessageRef revoked();

        @Key("granted")
        Granted grantedSection();

        @Name("Granted")
        interface Granted {

            @Name("Short")
            @Key("short")
            MessageRef shortMessage(@Arg("paid") Money paid, @Arg("days") long days, @Arg("until") Instant until);

            @Name("Admin")
            MessageRef admin(@Arg("days") long days, @Arg("until") Instant until);
        }
    }

    @Key("public")
    Public publicSection();

    @Name("Public")
    interface Public {

        @Name("Donation")
        MessageRef donation(@Arg("user") DiscordMemberContext user, @Arg("amount") Money amount);
    }

    Register register();

    @Name("Register")
    interface Register {

        @Name("Title")
        @Shown(Display.DISCORD_EMBED)
        MessageRef title();

        @Name("Team heading")
        @Shown(Display.DISCORD_EMBED)
        MessageRef teamHeading();

        @Name("Team")
        @Shown(Display.DISCORD_EMBED)
        MessageRef team();

        @Name("Name heading")
        @Shown(Display.DISCORD_EMBED)
        MessageRef nameHeading();

        @Name("Name")
        @Shown(Display.DISCORD_EMBED)
        MessageRef name();

        @Name("Partner heading")
        @Shown(Display.DISCORD_EMBED)
        MessageRef partnerHeading();

        @Name("Partner")
        @Shown(Display.DISCORD_EMBED)
        MessageRef partner();

        @Name("Button")
        @Shown(Display.DISCORD_BUTTON)
        @Format(TextFormat.PLAIN)
        MessageRef button();

        @Name("Success")
        MessageRef success(@Arg("name") @Example("Red_Fox") String name);

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
        @Format(TextFormat.PLAIN)
        MessageRef inviteButton();

        Register.Modal modal();

        @Name("Modal")
        @Shown(Display.DISCORD_MODAL)
        @Format(TextFormat.PLAIN)
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
            @Format(TextFormat.PLAIN)
            MessageRef pickerPlaceholder();

            @Name("Pick")
            @Shown(Display.DISCORD_SELECT)
            @Format(TextFormat.PLAIN)
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
            @Format(TextFormat.PLAIN)
            MessageRef accept();

            @Name("Decline")
            @Shown(Display.DISCORD_BUTTON)
            @Format(TextFormat.PLAIN)
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
        MessageRef preEvent(@Arg("teams") int teams);

        @Name("Start event")
        MessageRef startEvent(@Arg("teams") int teams, @Arg("players") int players);

        @Name("SMP")
        MessageRef smp(@Arg("players") int players);

        @Name("Maintenance")
        MessageRef maintenance();

        PreLaunch preLaunch();

        @Name("Pre launch")
        interface PreLaunch {

            @Name("Days")
            MessageRef days(@Arg("days") long days, @Arg("hours") int hours);

            @Name("Hours")
            MessageRef hours(@Arg("hours") long hours);

            @Name("Minutes")
            MessageRef minutes(@Arg("minutes") long minutes);

            @Name("Imminent")
            MessageRef imminent();

            @Name("Unknown")
            MessageRef unknown();
        }
    }

    Update update();

    /** The words of an update run drawn in the admin channel, and the tables that turn a report's enums into them. */
    @Name("Update")
    interface Update {

        Embed embed();

        /** The one-word headings of a run drawn in Discord. */
        @Name("Embed")
        interface Embed {

            @Name("Services")
            MessageRef services();

            @Name("Notes")
            MessageRef notes();

            @Name("Duration")
            MessageRef duration();

            @Name("Run")
            MessageRef run();

            @Name("By")
            MessageRef by();

            @Name("No build")
            MessageRef noBuild();

            @Name("More")
            MessageRef more(@Arg("count") int count);

            @Name("Elapsed")
            MessageRef elapsed(@Arg("took") Duration took);
        }

        /** The headline for a stage. */
        default MessageRef stage(final UpdateReport.Stage stage) {
            return switch (stage) {
                case RESOLVING -> stage().resolving();
                case PLANNED -> stage().planned();
                case COUNTDOWN -> stage().countdown();
                case STOPPING -> stage().stopping();
                case BACKING_UP -> stage().backingUp();
                case INSTALLING -> stage().installing();
                case STARTING -> stage().starting();
                case VERIFYING -> stage().verifying();
                case DONE -> stage().done();
                case NOTHING_TO_DO -> stage().nothingToDo();
                case FAILED -> stage().failed();
                case CANCELLED -> stage().cancelled();
            };
        }

        /** A service's state on its own, for a column that already names the service. */
        default MessageRef state(final UpdateReport.State state) {
            return switch (state) {
                case UNCHANGED -> state().unchanged();
                case PLANNED -> state().planned();
                case STOPPED -> state().stopped();
                case INSTALLED -> state().installed();
                case SAVED -> state().saved();
                case STARTING -> state().starting();
                case HEALTHY -> state().healthy();
                case FAILED -> state().failed();
            };
        }

        Stage stage();

        @Name("Stage")
        interface Stage {

            @Name("Resolving")
            @Key("RESOLVING")
            MessageRef resolving();

            @Name("Planned")
            @Key("PLANNED")
            MessageRef planned();

            @Name("Countdown")
            @Key("COUNTDOWN")
            MessageRef countdown();

            @Name("Stopping")
            @Key("STOPPING")
            MessageRef stopping();

            @Name("Backing up")
            @Key("BACKING_UP")
            MessageRef backingUp();

            @Name("Installing")
            @Key("INSTALLING")
            MessageRef installing();

            @Name("Starting")
            @Key("STARTING")
            MessageRef starting();

            @Name("Verifying")
            @Key("VERIFYING")
            MessageRef verifying();

            @Name("Done")
            @Key("DONE")
            MessageRef done();

            @Name("Nothing to do")
            @Key("NOTHING_TO_DO")
            MessageRef nothingToDo();

            @Name("Failed")
            @Key("FAILED")
            MessageRef failed();

            @Name("Cancelled")
            @Key("CANCELLED")
            MessageRef cancelled();
        }

        State state();

        @Name("State")
        interface State {

            @Name("Unchanged")
            @Key("UNCHANGED")
            MessageRef unchanged();

            @Name("Planned")
            @Key("PLANNED")
            MessageRef planned();

            @Name("Stopped")
            @Key("STOPPED")
            MessageRef stopped();

            @Name("Installed")
            @Key("INSTALLED")
            MessageRef installed();

            @Name("Saved")
            @Key("SAVED")
            MessageRef saved();

            @Name("Starting")
            @Key("STARTING")
            MessageRef starting();

            @Name("Healthy")
            @Key("HEALTHY")
            MessageRef healthy();

            @Name("Failed")
            @Key("FAILED")
            MessageRef failed();
        }
    }

    Announce announce();

    /** What the bot announces by itself. */
    @Name("Announce")
    interface Announce {

        @Name("Phase")
        MessageRef phase(
                @Arg("phase") @Example("smp") SeasonPhase phase,
                @Arg("previous") @Example("pre-event") SeasonPhase previous);
    }
}
