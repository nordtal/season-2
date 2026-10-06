package eu.nordtal.season.database;

import eu.nordtal.season.database.update.ByteSize;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * What a run's report says beside its lines: its notes and a line's detail, each value typed.
 *
 * A subsystem's own answer, such as Docker's or a backup's, stays its words, as a text value.
 */
@Name("Run reports")
public interface ReportTexts {

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

    @Name("A release's jar without its digest")
    MessageRef releaseJarWithoutDigest(@Arg("release") String release, @Arg("jar") String jar);

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
