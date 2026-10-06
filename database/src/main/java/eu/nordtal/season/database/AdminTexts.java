package eu.nordtal.season.database;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertChannel;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.alert.DiscordRole;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.payment.PaymentMatch;
import eu.nordtal.season.database.payment.PaymentRequestStatus;
import eu.nordtal.season.database.update.ByteSize;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.MessageSpecs;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.TextFormat;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.Mention;
import eu.nordtal.season.messages.value.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What an admin reads of the network, English only: the journal, the runs and their reports, the alerts and the notes.
 * Written for Discord, whose target escapes each value; a text here carries no markdown, so the page shows it as is.
 */
@MessageSpec(value = "admin", format = TextFormat.DISCORD_MARKDOWN, shown = Display.DISCORD_EMBED)
public interface AdminTexts {

    /** The texts; stateless, so one instance serves every caller. */
    AdminTexts TEXTS = MessageSpecs.create(AdminTexts.class);

    Journal journal();

    Run run();

    Alerts alert();

    Notes note();

    Report report();

    /**
     * One line per action, its values typed; the row stores the message, and each reader's target renders it.
     *
     * A value is named after the fact it states.
     */
    @Name("Journal")
    interface Journal {

        @Name("Action")
        MessageRef action(@Arg("action") JournalAction action);

        @Name("Who did it, when nobody pressed anything")
        MessageRef actor(@Arg("kind") Actor.Kind kind);

        @Name("Who did it, a person")
        MessageRef person(@Arg("person") Mention person);

        /** Who did it: a person as their mention, anyone else as the word for their kind. */
        default MessageRef who(final Actor actor) {
            final DiscordId person = actor.person();
            return person == null ? actor(actor.kind()) : person(Mention.of(person));
        }

        @Name("By, as a heading")
        MessageRef by();

        @Name("Minecraft account, as a heading")
        MessageRef minecraft();

        @Name("Concerns, as a heading")
        MessageRef concerns();

        @Name("A line from before typed values")
        MessageRef written(@Arg("detail") String detail);

        @Name("First admin")
        MessageRef adminRoot();

        @Name("Admin granted")
        MessageRef grantAdmin();

        @Name("Admin revoked")
        MessageRef revokeAdmin(@Arg("below") List<String> below, @Arg("count") int count);

        @Name("Pack enforced")
        MessageRef enforcePack();

        @Name("Pack exempted")
        MessageRef exemptPack();

        @Name("Access granted")
        MessageRef grantAccess(@Arg("days") int days, @Arg("until") Instant until);

        @Name("Access revoked")
        MessageRef revokeAccess(@Arg("grants") int grants);

        @Name("Play time set")
        MessageRef setPlaytime(@Arg("seconds") Duration seconds);

        @Name("Account linked")
        MessageRef link();

        @Name("Account unlinked")
        MessageRef unlink(@Arg("selfService") boolean selfService);

        @Name("Payment settled")
        MessageRef settle(
                @Arg("reference") String reference,
                @Arg("matched") PaymentMatch matched,
                @Arg("days") int days,
                @Arg("ordered") int ordered,
                @Arg("received") Money received,
                @Arg("donation") Money donation);

        @Name("Payment settled by hand")
        MessageRef settleByHand(
                @Arg("reference") String reference,
                @Arg("days") int days,
                @Arg("ordered") int ordered,
                @Arg("donation") Money donation);

        @Name("Phase changed")
        MessageRef setPhase(@Arg("from") SeasonPhase from, @Arg("to") SeasonPhase to);

        @Name("Phase changed, with a reason")
        MessageRef setPhaseBecause(
                @Arg("from") SeasonPhase from, @Arg("to") SeasonPhase to, @Arg("reason") String reason);

        @Name("Opening set")
        MessageRef setLaunch(@Arg("to") Instant to);

        @Name("Opening cleared")
        MessageRef clearLaunch();

        @Name("Paid time set")
        MessageRef setSmpStart(@Arg("to") Instant to, @Arg("movedGrants") long movedGrants);

        @Name("Paid time cleared")
        MessageRef clearSmpStart();

        @Name("Security key added")
        MessageRef registerKey(@Arg("label") String label);

        @Name("Security key used")
        MessageRef heldKey(@Arg("label") String label, @Arg("unlocked") boolean unlocked);

        @Name("Security key renamed")
        MessageRef renameKey(@Arg("label") String label);

        @Name("Security key removed")
        MessageRef removeKey(@Arg("label") String label, @Arg("left") int left);

        @Name("Security reset")
        MessageRef forgetFactors(@Arg("keys") int keys, @Arg("sessions") int sessions);

        @Name("Push switched on")
        MessageRef webPushSubscribe();

        @Name("Push switched off")
        MessageRef webPushUnsubscribe();

        @Name("Push tested")
        MessageRef webPushTest(@Arg("alert") AlertType alert);

        @Name("Alert preference")
        MessageRef setAlertPreference(
                @Arg("alert") AlertType alert, @Arg("channel") AlertChannel channel, @Arg("enabled") boolean enabled);

        @Name("Console line")
        MessageRef console(@Arg("service") String service, @Arg("command") String command);

        @Name("Settings saved")
        MessageRef saveSettings(@Arg("service") String service, @Arg("group") String group);

        @Name("Texts saved")
        MessageRef saveMessages(@Arg("bundle") String bundle);

        @Name("Plugin added")
        MessageRef addPlugin(@Arg("service") String service, @Arg("artifact") String artifact);

        @Name("Run cancelled")
        MessageRef cancelRun(@Arg("run") long run, @Arg("kind") UpdateKind kind);

        @Name("Backup downloaded")
        MessageRef downloadBackup(@Arg("archive") String archive, @Arg("size") MessageRef size);

        @Name("Backup downloaded, size unknown")
        MessageRef downloadBackupUnsized(@Arg("archive") String archive);

        @Name("Announcement")
        MessageRef announce(@Arg("languages") List<String> languages);

        @Name("Objective completed")
        MessageRef completeObjective(@Arg("objective") String objective);

        @Name("Milestone unlocked")
        MessageRef unlockMilestone(@Arg("milestone") String milestone);

        @Name("Hunger Games started")
        MessageRef startGame();
    }

    /** A run, as the journal, Steward's feed and the bot's update feed name it. */
    @Name("Runs")
    interface Run {

        @Name("Run kind")
        MessageRef kind(@Arg("kind") UpdateKind kind);

        @Name("Run status")
        MessageRef status(@Arg("status") UpdateStatus status);

        @Name("Run stage")
        MessageRef stage(@Arg("stage") UpdateReport.Stage stage);

        @Name("Services that came back")
        MessageRef successful(@Arg("successful") long successful, @Arg("total") long total);

        @Name("A service's state in a run")
        MessageRef state(@Arg("state") UpdateReport.State state);

        @Name("Run, as a heading")
        MessageRef heading();

        @Name("Services, as a heading")
        MessageRef services();

        @Name("Notes, as a heading")
        MessageRef notes();

        @Name("Duration, as a heading")
        MessageRef duration();

        @Name("How long a run took")
        MessageRef took(@Arg("took") Duration took);

        @Name("An artefact with no build for this Minecraft version")
        MessageRef noBuild();

        @Name("Lines left out")
        MessageRef more(@Arg("count") int count);
    }

    /** An alert: its title and the lines below it, which a lock screen, the admin channel and the page render. */
    @Name("Alerts")
    interface Alerts {

        @Name("Level")
        MessageRef level(@Arg("level") Alert.Level level);

        @Name("Words as they came: a program's answer, or a line from before typed alerts")
        MessageRef words(@Arg("text") String text);

        @Name("All clear")
        MessageRef clear(@Arg("type") AlertType type);

        @Name("Several of one type at once")
        MessageRef several(@Arg("subjects") List<String> subjects, @Arg("count") int count);

        @Name("A run failed")
        MessageRef runFailed(@Arg("kind") UpdateKind kind);

        @Name("Which run")
        MessageRef run(@Arg("run") long run);

        @Name("No services")
        MessageRef noServices();

        @Name("A service is not running")
        MessageRef notRunning(@Arg("service") String service);

        @Name("A service is unhealthy")
        MessageRef unhealthy(@Arg("service") String service);

        @Name("Failing healthcheck")
        MessageRef healthFails();

        @Name("Docker's word")
        MessageRef dockerState(@Arg("state") String state);

        @Name("Older images")
        MessageRef olderImage(@Arg("services") List<String> services, @Arg("count") int count);

        @Name("Images not compared")
        MessageRef notCompared();

        @Name("No finished backup")
        MessageRef noBackup();

        @Name("Only started backups")
        MessageRef onlyStarted();

        @Name("No database dump")
        MessageRef noDump();

        @Name("What a missing dump means")
        MessageRef dumpMatters();

        @Name("No archive of a volume")
        MessageRef noArchive(@Arg("volume") String volume);

        @Name("An old dump")
        MessageRef oldDump(@Arg("hours") long hours);

        @Name("An old archive")
        MessageRef oldArchive(@Arg("volume") String volume, @Arg("hours") long hours);

        @Name("No copy off the host")
        MessageRef noOffsite();

        @Name("An old copy off the host")
        MessageRef oldOffsite(@Arg("hours") long hours);

        @Name("The permitted age")
        MessageRef permittedAge(@Arg("hours") long hours);

        @Name("A full disk")
        MessageRef disk(@Arg("percent") long percent);

        @Name("Used memory")
        MessageRef memory(@Arg("percent") long percent);

        @Name("The threshold")
        MessageRef threshold(@Arg("percent") long percent);

        @Name("No memory limit")
        MessageRef noLimit();

        @Name("A payment needs a look")
        MessageRef payment();

        @Name("An unknown reference")
        MessageRef unknownReference(
                @Arg("payment") String payment, @Arg("amount") Money amount, @Arg("reference") String reference);

        @Name("A request no longer open")
        MessageRef notOpen(
                @Arg("payment") String payment,
                @Arg("amount") Money amount,
                @Arg("reference") String reference,
                @Arg("status") PaymentRequestStatus status);

        @Name("A payment claimed twice")
        MessageRef claimed(
                @Arg("payment") String payment, @Arg("amount") Money amount, @Arg("reference") String reference);

        @Name("A payment below every tier")
        MessageRef noTier(
                @Arg("payment") String payment, @Arg("amount") Money amount, @Arg("reference") String reference);

        @Name("Several roles of one name")
        MessageRef roleAmbiguous(@Arg("name") String name);

        @Name("None of them taken")
        MessageRef noneAdopted();

        @Name("A role not created")
        MessageRef roleNotCreated(@Arg("name") String name);

        @Name("The lock without its channel")
        MessageRef lockWithoutChannel();

        @Name("Nobody locked")
        MessageRef nobodyLocked();

        @Name("Channels the lock does not close")
        MessageRef lockNotKept(@Arg("count") int count);

        @Name("Which channel, and what went wrong")
        MessageRef failedIn(@Arg("channel") String channel, @Arg("error") String error);

        @Name("A role not changed")
        MessageRef roleNotChanged(@Arg("role") DiscordRole role, @Arg("given") boolean given);

        @Name("Who, and what went wrong")
        MessageRef failedFor(@Arg("person") Mention person, @Arg("error") String error);

        @Name("A direct message not delivered")
        MessageRef dm();

        @Name("To whom")
        MessageRef to(@Arg("person") Mention person);

        @Name("A link failed")
        MessageRef linkFailed();

        @Name("A purchase failed")
        MessageRef purchaseFailed();

        @Name("bunq refused a payment link")
        MessageRef linkRefused();

        @Name("Which request, and what bunq said")
        MessageRef refused(@Arg("reference") String reference, @Arg("error") String error);
    }

    /** A card the bot posts to the admin channel for later, without a mention. */
    @Name("Notes")
    interface Notes {

        @Name("Payment booked")
        MessageRef paymentBooked();

        @Name("What a booked payment bought")
        MessageRef booked(
                @Arg("reference") String reference,
                @Arg("payer") Mention payer,
                @Arg("days") int days,
                @Arg("until") Instant until);

        @Name("Too many wrong link codes")
        MessageRef tooManyCodes();

        @Name("Refused linking for the hour")
        MessageRef refusedForTheHour(@Arg("person") Mention person);

        @Name("No season start")
        MessageRef noSeasonStart();

        @Name("Access anchored to the grant")
        MessageRef runsFromTheGrant(@Arg("person") Mention person, @Arg("from") Instant from);
    }

    /**
     * What a run's report says beside its lines: its notes and a line's detail, each value typed.
     *
     * A subsystem's own answer, such as Docker's or a backup's, stays its words, as a text value.
     */
    @Name("Run reports")
    interface Report {

        @Name("Words as they came: a subsystem's answer, or a line from before typed reports")
        MessageRef words(@Arg("text") String text);

        @Name("The agent carrying out a run is gone")
        MessageRef orphaned();

        @Name("The database was restored over an open run")
        MessageRef restoredOver(@Arg("dump") String dump);

        @Name("A run failed unexpectedly")
        MessageRef failedUnexpectedly(@Arg("error") String error);

        @Name("Stopped during the countdown")
        MessageRef cancelled();

        @Name("Standbys ready")
        MessageRef standbysReady(@Arg("standbys") List<String> standbys, @Arg("run") UpdateReport.Undertaking run);

        @Name("No standby, so nothing was done")
        MessageRef noStandby(@Arg("run") UpdateReport.Undertaking run);

        @Name("A service did not stop, so nothing was done")
        MessageRef notStopped(@Arg("run") UpdateReport.Undertaking run, @Arg("services") List<String> services);

        @Name("An unverified stop")
        MessageRef unverifiedStop(
                @Arg("services") List<String> services,
                @Arg("run") UpdateReport.Undertaking run,
                @Arg("failsTheRun") boolean failsTheRun);

        @Name("A standby did not start")
        MessageRef standbyNotStarted(@Arg("standby") String standby, @Arg("reason") String reason);

        @Name("Standbys not healthy in time")
        MessageRef standbysUnhealthy(@Arg("standbys") List<MessageRef> standbys, @Arg("minutes") long minutes);

        @Name("A standby and what was seen of it")
        MessageRef standbySeen(@Arg("standby") String standby, @Arg("seen") MessageRef seen);

        @Name("Stopped while waiting for the standbys")
        MessageRef standbysInterrupted(@Arg("standbys") List<String> standbys);

        @Name("Stopped while the players were moved")
        MessageRef evacuationInterrupted(@Arg("services") List<String> services);

        @Name("Stopped with players still on")
        MessageRef stoppedWithPlayers(
                @Arg("players") long players, @Arg("servers") List<String> servers, @Arg("seconds") long seconds);

        @Name("No player count")
        MessageRef playersUnknown(@Arg("services") List<String> services, @Arg("seconds") long seconds);

        @Name("A standby stopped again")
        MessageRef standbyStopped(@Arg("standby") String standby);

        @Name("A standby stopped with players on it")
        MessageRef standbyStoppedWithPlayers(
                @Arg("standby") String standby, @Arg("players") long players, @Arg("seconds") long seconds);

        @Name("A standby stopped while players were on it")
        MessageRef standbyStoppedInterrupted(@Arg("standby") String standby, @Arg("players") long players);

        @Name("A standby left running without a container")
        MessageRef standbyNoContainer(@Arg("standby") String standby);

        @Name("A standby left running")
        MessageRef standbyNotStopped(@Arg("standby") String standby, @Arg("reason") String reason);

        @Name("Held services left out of an update")
        MessageRef heldLeftOut(@Arg("services") List<String> services);

        @Name("A release published during the hand-over")
        MessageRef releasedMeanwhile(@Arg("release") String release, @Arg("own") String own);

        @Name("An older release")
        MessageRef olderRelease(@Arg("release") String release, @Arg("own") String own);

        @Name("Handed to a one-shot")
        MessageRef handed(@Arg("release") String release);

        @Name("The one-shot did not start")
        MessageRef oneShotNotStarted(@Arg("release") String release, @Arg("reason") String reason);

        @Name("The schema could not be migrated")
        MessageRef notMigrated(@Arg("reason") String reason);

        @Name("An artefact not installed")
        MessageRef notInstalled(@Arg("artefact") String artefact, @Arg("reason") String reason);

        @Name("Nothing to restart, all held")
        MessageRef restartAllHeld();

        @Name("Nothing to restart in the scope")
        MessageRef restartNoneInScope(@Arg("scope") List<String> scope);

        @Name("Held services not restarted")
        MessageRef heldNotRestarted(@Arg("services") List<String> services);

        @Name("What a backup saves is unknown")
        MessageRef backupUnread(@Arg("reason") String reason);

        @Name("No volume to back up")
        MessageRef noBackupVolumes();

        @Name("No offsite target")
        MessageRef noOffsite();

        @Name("Old archives removed")
        MessageRef pruned(
                @Arg("daily") int daily,
                @Arg("weekly") int weekly,
                @Arg("monthly") int monthly,
                @Arg("count") int count,
                @Arg("archives") List<String> archives);

        @Name("A take-down naming nothing")
        MessageRef downUnnamed();

        @Name("A take-down of what cannot go down")
        MessageRef downRefused(@Arg("services") List<String> services);

        @Name("Held down")
        MessageRef heldDown(@Arg("services") List<String> services);

        @Name("Nothing held to start")
        MessageRef nothingHeld();

        @Name("A remake naming nothing")
        MessageRef remakeUnnamed(@Arg("kind") UpdateKind kind);

        @Name("A remake of the agent")
        MessageRef remakeAgent();

        @Name("A remake of an unknown service")
        MessageRef remakeUnknown(@Arg("services") List<String> services);

        @Name("Held services left out of a remake")
        MessageRef heldNotRemade(@Arg("services") List<String> services);

        @Name("A removal naming no plugin")
        MessageRef removalUnnamed();

        @Name("A removal of an unknown plugin")
        MessageRef removalUnknown(@Arg("service") String service, @Arg("artifact") String artifact);

        @Name("A plugin with nothing installed")
        MessageRef removalEmpty(@Arg("artifact") String artifact);

        @Name("Files removed")
        MessageRef removed(@Arg("files") List<String> files);

        @Name("A plugin not removed")
        MessageRef removalFailed(@Arg("reason") String reason);

        @Name("A restore naming no archive")
        MessageRef restoreUnnamed();

        @Name("A restore of an unknown archive")
        MessageRef restoreUnknown(@Arg("archive") String archive);

        @Name("A restore into a volume not backed up")
        MessageRef restoreNotAVolume(@Arg("volume") String volume, @Arg("archive") String archive);

        @Name("A volume not saved before its restore")
        MessageRef restoreUnsaved(@Arg("volume") String volume);

        @Name("A database not saved before its restore")
        MessageRef restoreDatabaseUnsaved(@Arg("reason") String reason);

        @Name("A restore failed")
        MessageRef restoreFailed();

        @Name("No container to stop")
        MessageRef noContainerToStop();

        @Name("A stop failed")
        MessageRef stopFailed(@Arg("reason") String reason);

        @Name("No container to start")
        MessageRef noContainerToStart();

        @Name("A start failed")
        MessageRef startFailed(@Arg("reason") String reason);

        @Name("Recreating a container")
        MessageRef recreating(@Arg("pull") boolean pull);

        @Name("An image the registry has a newer one of, as a change")
        MessageRef imageOutdated();

        @Name("A container made again, as a change")
        MessageRef madeAgain(@Arg("pull") boolean pull);

        @Name("A server restarted with nothing changed, as a change")
        MessageRef nothingChanges();

        @Name("A volume being saved, as a change")
        MessageRef saving();

        @Name("A server stopped while its volume is saved, as a change")
        MessageRef stoppedWhileSaving();

        @Name("Something saved, as a change")
        MessageRef saved(@Arg("size") MessageRef size, @Arg("took") Duration took);

        @Name("Something not saved, as a change")
        MessageRef notSaved();

        @Name("A size in bytes")
        MessageRef size(@Arg("amount") BigDecimal amount, @Arg("unit") ByteSize.Unit unit);

        @Name("A server taken down and held, as a change")
        MessageRef staysDown();

        @Name("A held server started again, as a change")
        MessageRef startsAgain();

        @Name("A plugin removed, as a change")
        MessageRef pluginRemoved();

        @Name("A server stopped for a restore, as a change")
        MessageRef stoppedForRestore(@Arg("archive") String archive);

        @Name("An archive put back, as a change")
        MessageRef restored(@Arg("size") MessageRef size);

        @Name("An archive not put back, as a change")
        MessageRef notRestored();

        @Name("No image could be read")
        MessageRef imagesUnread(@Arg("answer") String answer);

        @Name("No image compared with a registry")
        MessageRef imagesUncompared();

        @Name("Images a registry could not be asked about")
        MessageRef imagesUnverifiable(@Arg("services") List<String> services, @Arg("count") int count);

        @Name("Images built on this host")
        MessageRef imagesLocal(@Arg("services") List<String> services, @Arg("count") int count);

        @Name("Not recreated, and nothing to go back to")
        MessageRef notRecreated(@Arg("outdated") boolean outdated, @Arg("reason") String reason);

        @Name("Back on the old image, not seen yet")
        MessageRef fellBack(@Arg("outdated") boolean outdated, @Arg("reason") String reason);

        @Name("Back on the old image and running")
        MessageRef fellBackHealthy(@Arg("outdated") boolean outdated, @Arg("reason") String reason);

        @Name("Back on the old image and not running")
        MessageRef fellBackDown(
                @Arg("outdated") boolean outdated,
                @Arg("reason") String reason,
                @Arg("minutes") long minutes,
                @Arg("seen") MessageRef seen);

        @Name("Back on the old image, not seen before the agent stopped")
        MessageRef fellBackInterrupted(@Arg("outdated") boolean outdated, @Arg("reason") String reason);

        @Name("Not even back on the old image")
        MessageRef notFellBack(
                @Arg("outdated") boolean outdated, @Arg("reason") String reason, @Arg("answer") String answer);

        @Name("Not healthy in time")
        MessageRef notHealthy(@Arg("minutes") long minutes, @Arg("seen") MessageRef seen);

        @Name("A service with no container in the project, as what was seen of it")
        MessageRef noContainer();

        @Name("The container runtime unread, as what was seen of a service")
        MessageRef runtimeUnread(@Arg("answer") String answer);

        @Name("Stopped while waiting for a service")
        MessageRef waitInterrupted();

        @Name("Recreating the agent")
        MessageRef renewingAgent();

        @Name("The agent not recreated")
        MessageRef agentNotRenewed(@Arg("reason") String reason);

        @Name("A foreign image not recreated")
        MessageRef foreignNotRecreated(@Arg("reason") String reason);

        @Name("Newer foreign images")
        MessageRef foreignNewer(@Arg("services") List<String> services, @Arg("count") int count);

        @Name("The resource pack moves")
        MessageRef packMoves(@Arg("version") String version);

        @Name("The resource pack not checked")
        MessageRef packUnchecked(@Arg("reason") @Example("report.no-database") MessageRef reason);

        @Name("Not in the release")
        MessageRef notInRelease(
                @Arg("service") String service,
                @Arg("reason") @Example("report.release-without-pack") MessageRef reason,
                @Arg("installed") String installed);

        @Name("A jar nothing claims")
        MessageRef unclaimed(@Arg("service") String service, @Arg("file") String file);

        @Name("Held back")
        MessageRef heldBack(
                @Arg("reason") @Example("report.no-database") MessageRef reason, @Arg("held") List<String> held);

        @Name("The latest release unread")
        MessageRef releaseUnread(@Arg("repo") String repo, @Arg("error") String error);

        @Name("A release without a jar")
        MessageRef releaseWithoutJar(@Arg("release") String release, @Arg("artefact") String artefact);

        @Name("A release without a pack")
        MessageRef releaseWithoutPack(@Arg("release") String release);

        @Name("A release's pack without its SHA-1")
        MessageRef releaseWithoutSha1(@Arg("release") String release, @Arg("zip") String zip);

        @Name("A pack's SHA-1 unread")
        MessageRef sha1Unread(@Arg("file") String file, @Arg("error") String error);

        @Name("No build for this Minecraft version")
        MessageRef noBuild(
                @Arg("artefact") String artefact, @Arg("minecraft") String minecraft, @Arg("loader") String loader);

        @Name("No source answered")
        MessageRef noSource(@Arg("artefact") String artefact);

        @Name("A volume not mounted")
        MessageRef notMounted(@Arg("directory") String directory);

        @Name("No database to read the proxy's pack from")
        MessageRef noDatabase();

        @Name("The proxy's pack unread")
        MessageRef proxyPackUnread(@Arg("error") String error);

        @Name("The proxy has no pack")
        MessageRef proxyWithoutPack();

        @Name("The proxy's pack has no SHA-1")
        MessageRef proxyPackWithoutSha1();

        @Name("Velocity ahead of the proxy's API")
        MessageRef velocityAhead(@Arg("version") String version, @Arg("api") String api);

        @Name("An archive taken after an unverified stop")
        MessageRef unverifiedArchive(@Arg("mark") String mark);

        @Name("An unverified archive without its mark")
        MessageRef unmarkedArchive(@Arg("services") List<String> services);
    }
}
