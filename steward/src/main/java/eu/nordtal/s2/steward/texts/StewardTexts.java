package eu.nordtal.s2.steward.texts;

import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.internalapi.agent.ImageResult;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Display;
import eu.nordtal.s2.messages.spec.MessageSpec;
import eu.nordtal.s2.messages.spec.MessageSpecs;
import eu.nordtal.s2.messages.spec.Name;
import eu.nordtal.s2.messages.spec.TextFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Steward's own texts, English only: the page renders them in the browser and an admin may override any of them.
 * The frontend names a key as a string, which the generated {@code texts.gen.ts} types; the README says why.
 */
@MessageSpec(value = "steward", format = TextFormat.PLAIN, shown = Display.STEWARD)
public interface StewardTexts {

    /** The texts; stateless, so one instance serves every caller. */
    StewardTexts TEXTS = MessageSpecs.create(StewardTexts.class);

    Steward steward();

    @Name("Steward")
    interface Steward {

        Service service();

        Artifact artifact();

        Image image();

        Answer answer();

        Said said();

        Alerts alerts();

        Journal journal();

        Operations operations();

        Updates updates();

        @Name("Service")
        interface Service {

            @Name("Service state")
            MessageRef state(@Arg("state") ServiceWord state);

            @Name("Docker's word")
            MessageRef dockerState(@Arg("state") String state);

            @Name("Held down")
            MessageRef heldSince(@Arg("since") Instant since, @Arg("state") String state);

            @Name("Failing healthcheck")
            MessageRef unhealthy();

            @Name("No verdict yet")
            MessageRef starting();

            @Name("Healthcheck")
            MessageRef health(@Arg("health") String health);

            @Name("No healthcheck")
            MessageRef noHealth();

            @Name("Not read yet")
            MessageRef notRead();
        }

        @Name("Artifact")
        interface Artifact {

            @Name("Artifact status")
            MessageRef status(@Arg("status") ArtifactStatus status);

            @Name("Artifact status explained")
            MessageRef statusTip(@Arg("status") ArtifactStatus status);
        }

        @Name("Image")
        interface Image {

            @Name("Image drift")
            MessageRef drift(@Arg("drift") ImageResult.State drift);

            @Name("Image drift explained")
            MessageRef driftTip(@Arg("drift") ImageResult.State drift);

            @Name("Image")
            MessageRef label();
        }

        /** Why Steward refuses a request a person sent; the error it answers carries the text rendered. */
        @Name("Answer")
        interface Answer {

            @Name("No longer an admin")
            MessageRef notAdmin();

            @Name("Already an admin")
            MessageRef alreadyAdmin();

            @Name("Not a member of the guild")
            MessageRef notAMember();

            @Name("Too many grants this hour")
            MessageRef grantsPerHour(@Arg("count") int count);

            @Name("Revoking oneself")
            MessageRef self();

            @Name("Not granted below")
            MessageRef notBelow();

            @Name("Exempt already")
            MessageRef exemptAlready();

            @Name("Enforced already")
            MessageRef enforcedAlready();

            @Name("Nobody by that id")
            MessageRef unknownPerson();

            @Name("No database")
            MessageRef noDatabase(@Arg("kept") Kept kept);

            @Name("No sources")
            MessageRef noSources();

            @Name("No such request")
            MessageRef noRequest(@Arg("request") String request);

            @Name("No such announcement")
            MessageRef noAnnouncement(@Arg("announcement") long announcement);

            @Name("No such bundle")
            MessageRef noBundle(@Arg("bundle") String bundle);

            @Name("Not the JSON taken")
            MessageRef notJson();

            @Name("Empty body")
            MessageRef empty();

            @Name("Empty announcement text")
            MessageRef emptyText(@Arg("language") String language);

            @Name("Countdown over")
            MessageRef tooLate();

            @Name("Key not held")
            MessageRef keyNotHeld();

            @Name("Key not held lately")
            MessageRef keyNotRecent(@Arg("within") Duration within);

            @Name("No key")
            MessageRef noKey();

            @Name("Key first")
            MessageRef keyFirst();

            @Name("Ceremony from elsewhere")
            MessageRef ceremonyElsewhere(@Arg("registration") boolean registration);

            @Name("No such key")
            MessageRef noSuchKey();

            @Name("Sign-in not configured")
            MessageRef signInUnconfigured(@Arg("missing") String missing);

            @Name("Sign-in from elsewhere")
            MessageRef signInElsewhere();

            @Name("No code")
            MessageRef noCode();

            @Name("Not in the guild")
            MessageRef notInGuild();

            @Name("In the guild, not an admin")
            MessageRef notAnAdmin(@Arg("name") String name);

            @Name("Discord refused the sign-in")
            MessageRef signInRefused(@Arg("status") int status, @Arg("uri") String uri);

            @Name("No access token")
            MessageRef noAccessToken();

            @Name("Discord answered")
            MessageRef discordAnswered(@Arg("status") int status);

            @Name("Discord unreachable")
            MessageRef discordUnreachable(@Arg("error") String error);

            @Name("Interrupted")
            MessageRef interrupted();

            @Name("Bot token refused")
            MessageRef botTokenRefused();

            @Name("Guild not readable")
            MessageRef guildUnreadable();

            @Name("Unknown guild")
            MessageRef unknownGuild();

            @Name("Rate limited")
            MessageRef rateLimited();
        }

        /** What Steward tells a person who did something, as data the page renders. */
        @Name("Said")
        interface Said {

            @Name("A setting saved")
            MessageRef setting(
                    @Arg("network") boolean network, @Arg("service") String service, @Arg("live") boolean live);

            @Name("A message saved")
            MessageRef message();

            @Name("An announcement's line")
            MessageRef announced(@Arg("posted") boolean posted, @Arg("language") String language);

            @Name("No guild id")
            MessageRef noGuildId();

            @Name("No bot token")
            MessageRef noBotToken();

            @Name("The guild not listed")
            MessageRef guildNotListed();

            @Name("A service's own words")
            MessageRef words(@Arg("text") String text);
        }

        /** The alerts page's own words; an alert itself is told in the admin bundle. */
        @Name("Alerts page")
        interface Alerts {

            @Name("Title")
            MessageRef title();

            @Name("Now")
            MessageRef now();

            @Name("Recent")
            MessageRef recent();

            @Name("Nothing wrong")
            MessageRef allClear();

            @Name("Nothing raised")
            MessageRef noneRaised();

            @Name("Stack unreadable")
            MessageRef unreadable();

            @Name("Raised by")
            MessageRef raisedBy(@Arg("who") String who);
        }

        /** The journal page's own words; a line of it is told in the admin bundle. */
        @Name("Journal page")
        interface Journal {

            @Name("Title")
            MessageRef title();

            @Name("Entries")
            MessageRef entries();

            @Name("Every action")
            MessageRef all();

            @Name("Subject field")
            MessageRef subject();

            @Name("Subject placeholder")
            MessageRef exactId();

            @Name("Filter")
            MessageRef filter();

            @Name("Reset")
            MessageRef reset();

            @Name("Nothing matches")
            MessageRef noEntry();

            @Name("When")
            MessageRef when();

            @Name("Action")
            MessageRef action();

            @Name("Triggered by")
            MessageRef actor();

            @Name("Concerns")
            MessageRef concerns();

            @Name("Detail")
            MessageRef detail();

            @Name("How many")
            MessageRef count(@Arg("count") int count, @Arg("limit") int limit);
        }

        /** The words of a run's page and of asking for one; a run's own words are the admin bundle's. */
        @Name("Operations page")
        interface Operations {

            @Name("A stage on the trail")
            MessageRef step(@Arg("stage") UpdateReport.Stage stage);

            @Name("Report unreadable")
            MessageRef reportUnreadable();

            @Name("Nothing written yet")
            MessageRef nothingWritten();

            @Name("Nothing to do")
            MessageRef nothingToDo();

            @Name("Saved")
            MessageRef saved(@Arg("count") int count);

            @Name("Moved")
            MessageRef moved(@Arg("services") int services, @Arg("artefacts") int artefacts);

            @Name("Failed")
            MessageRef failed(@Arg("count") int count);

            @Name("No line")
            MessageRef noLine();

            @Name("No change")
            MessageRef noChange();

            @Name("Ask for a run")
            MessageRef ask(@Arg("kind") UpdateKind kind);

            @Name("What a run does")
            MessageRef askWhat(@Arg("kind") UpdateKind kind);

            @Name("What a run costs")
            MessageRef askWarning(@Arg("kind") UpdateKind kind);

            @Name("For some services")
            MessageRef scoped(@Arg("ask") String ask, @Arg("services") List<String> services);

            @Name("Now")
            MessageRef now();

            @Name("Entered")
            MessageRef entered(@Arg("kind") String kind, @Arg("run") long run);

            @Name("Cancelled")
            MessageRef cancelled(@Arg("run") long run);

            @Name("Cancelled, said")
            MessageRef cancelledNote();

            @Name("Not cancelled")
            MessageRef notCancelled(@Arg("run") long run);

            @Name("Cancel")
            MessageRef cancel();

            @Name("A run's title")
            MessageRef run(@Arg("id") String id);

            @Name("A run")
            MessageRef aRun();

            @Name("All updates")
            MessageRef allUpdates();

            @Name("Not a run number")
            MessageRef notANumber();

            @Name("Not a run number, said")
            MessageRef notANumberNote(@Arg("id") String id);

            @Name("Status")
            MessageRef status();

            @Name("Requested by")
            MessageRef requestedBy();

            @Name("No earlier than")
            MessageRef noEarlierThan();

            @Name("No earlier than, said")
            MessageRef noEarlierThanHint();

            @Name("Started")
            MessageRef started();

            @Name("Duration")
            MessageRef duration();

            @Name("Still running")
            MessageRef stillRunning();

            @Name("Nothing to do, as a title")
            MessageRef nothingToDoTitle();

            @Name("Nothing to do, said")
            MessageRef nothingToDoNote();

            @Name("Saved nothing")
            MessageRef savedNothing();

            @Name("Saved nothing, said")
            MessageRef savedNothingNote();

            @Name("Stages")
            MessageRef stages();

            @Name("Growing")
            MessageRef growing();

            @Name("Report")
            MessageRef report();

            @Name("Raw report")
            MessageRef rawReport();

            @Name("No report")
            MessageRef noReport();

            @Name("No report, said")
            MessageRef noReportNote();

            @Name("No line, as a title")
            MessageRef noLineTitle();

            @Name("No line, said")
            MessageRef noLineNote();

            @Name("Service")
            MessageRef service();

            @Name("State")
            MessageRef state();

            @Name("Changes")
            MessageRef changes();

            @Name("No build")
            MessageRef noBuild();

            @Name("New")
            MessageRef added();

            @Name("Notes")
            MessageRef notes();

            @Name("Copied")
            MessageRef copied();

            @Name("Copy")
            MessageRef copy();

            @Name("Command copied")
            MessageRef commandCopied();

            @Name("Cannot copy")
            MessageRef cannotCopy();

            @Name("Cannot copy, said")
            MessageRef cannotCopyNote();
        }

        /** The updates page's own words. */
        @Name("Updates page")
        interface Updates {

            @Name("Title")
            MessageRef title();

            @Name("Check again, said")
            MessageRef checkAgainTip();

            @Name("Could not check again")
            MessageRef checkAgainFailed();

            @Name("Check again")
            MessageRef checkAgain();

            @Name("Restart everything")
            MessageRef restartEverything();

            @Name("Update everything")
            MessageRef updateEverything();

            @Name("Available")
            MessageRef available();

            @Name("A source silent")
            MessageRef sourceSilent();

            @Name("Checked")
            MessageRef checked();

            @Name("Next")
            MessageRef next();

            @Name("Not scheduled")
            MessageRef notScheduled();

            @Name("Nothing installed yet")
            MessageRef nothing();

            @Name("Nothing to install")
            MessageRef nothingToInstall();

            @Name("Nothing to install, said")
            MessageRef nothingToInstallNote();

            @Name("Incomplete")
            MessageRef incomplete();

            @Name("Service")
            MessageRef service();

            @Name("Plugin")
            MessageRef plugin();

            @Name("Change")
            MessageRef change();

            @Name("State")
            MessageRef state();

            @Name("The resource pack")
            MessageRef resourcePack();

            @Name("Claimed by nothing")
            MessageRef unclaimed(@Arg("files") List<String> files);

            @Name("Runs")
            MessageRef runs();

            @Name("No run yet")
            MessageRef noRun();

            @Name("No run yet, said")
            MessageRef noRunNote();

            @Name("Run")
            MessageRef run();

            @Name("When")
            MessageRef when();

            @Name("Kind")
            MessageRef kind();

            @Name("Status")
            MessageRef status();

            @Name("Result")
            MessageRef result();

            @Name("Initiated by")
            MessageRef initiatedBy();

            @Name("Schedule")
            MessageRef schedule();

            @Name("Schedule, said")
            MessageRef scheduleNote();

            @Name("No update section")
            MessageRef noSection();

            @Name("No update section, said")
            MessageRef noSectionNote();

            @Name("Days")
            MessageRef days();

            @Name("No day")
            MessageRef noDay();

            @Name("Schedule saved")
            MessageRef scheduleSaved();

            @Name("Changed meanwhile")
            MessageRef changedMeanwhile();

            @Name("Save")
            MessageRef save();

            @Name("Changed")
            MessageRef changed(@Arg("count") int count);
        }
    }

    /** What Steward keeps in its database, named when it has none. */
    enum Kept {
        ALERTS,
        PREFERENCES,
        SETTINGS,
        PLUGINS,
        OVERRIDES
    }

    /** The words a service's badge and dot say: Docker's own states, and the ones its health is folded into. */
    enum ServiceWord {
        RUNNING,
        HEALTHY,
        STARTING,
        UNHEALTHY,
        HELD,
        STANDBY,
        CREATED,
        RESTARTING,
        REMOVING,
        PAUSED,
        EXITED,
        DEAD
    }

    /** What a source says of one artifact, as the agent's plan names it. */
    enum ArtifactStatus {
        UP_TO_DATE,
        OUTDATED,
        MISSING,
        UNSUPPORTED,
        MOUNT_MISSING,
        UNRESOLVED
    }
}
