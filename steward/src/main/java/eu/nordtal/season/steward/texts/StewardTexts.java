package eu.nordtal.season.steward.texts;

import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.MessageSpecs;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.TextFormat;
import java.time.Duration;
import java.time.Instant;

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

        AlertsPage alerts();

        JournalPage journal();

        OperationsPage operations();

        UpdatesPage updates();

        BackupsPage backups();

        Forms form();

        GameTexts game();

        SettingsEditor settings();

        MessageEditorTexts messageEditor();

        AnnouncementsPage announcements();

        Failures failure();

        NetworkPanel network();

        IdentityCard identity();

        Shell shell();

        NotificationsPanel notifications();

        OverviewPage overview();

        SeasonPage season();

        ServicePage servicePage();

        KeysPage keys();

        PeoplePage people();

        PaymentsPage payments();

        BackupDialogs backupSettings();

        @Name("Service")
        interface Service {

            @Name("Service state")
            MessageRef state(@Arg("state") ServiceWord state);

            @Name("Docker's word")
            MessageRef dockerState(@Arg("state") String state);

            @Name("Held down")
            MessageRef heldSince(@Arg("since") Instant since, @Arg("state") String state);

            @Name("How a one-shot ended")
            MessageRef exited(@Arg("code") int code, @Arg("at") Instant at);

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

            @Name("Jars built here")
            MessageRef localJars();

            @Name("A local build explained")
            MessageRef localTip();
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

            @Name("Agent not configured")
            MessageRef agentUnconfigured();

            @Name("No such service")
            MessageRef noService(@Arg("service") String service);

            @Name("No such settings group")
            MessageRef noSettings(@Arg("service") String service, @Arg("group") String group);

            @Name("No such setting")
            MessageRef noSetting(@Arg("group") String group, @Arg("path") String path);

            @Name("Settings changed meanwhile")
            MessageRef settingsChanged(@Arg("service") String service, @Arg("group") String group);

            @Name("A secret setting")
            MessageRef secretSetting(@Arg("path") String path);

            @Name("A setting of another type")
            MessageRef notOfType(
                    @Arg("path") String path, @Arg("expected") Expected expected, @Arg("text") String text);

            @Name("A section that has to stay")
            MessageRef keepSection(@Arg("path") String path, @Arg("field") String field, @Arg("value") String value);

            @Name("No such backup")
            MessageRef noBackup(@Arg("backup") String backup);

            @Name("Backup not finished")
            MessageRef backupUnfinished(@Arg("backup") String backup);

            @Name("Restore not confirmed")
            MessageRef confirmRestore(@Arg("replaces") String replaces);

            @Name("No such message")
            MessageRef noMessage(@Arg("bundle") String bundle, @Arg("key") String key);

            @Name("Override refused")
            MessageRef overrideRefused(@Arg("key") String key, @Arg("language") String language);

            @Name("Preview refused")
            MessageRef previewRefused(@Arg("key") String key, @Arg("language") String language);

            @Name("Nowhere to preview")
            MessageRef noPreview(@Arg("key") String key);

            @Name("No player to preview to")
            MessageRef previewUnlinked();

            @Name("Player not in a game")
            MessageRef previewOffline();

            @Name("Grant too long")
            MessageRef grantDays(@Arg("most") int most);

            @Name("Play time out of range")
            MessageRef playtimeRange(@Arg("most") Duration most);

            @Name("Announcement too long")
            MessageRef tooLong(@Arg("language") String language, @Arg("most") int most);

            @Name("Web push not configured")
            MessageRef pushUnconfigured();

            @Name("No such push subscription")
            MessageRef noSubscription();

            @Name("Push subscription gone")
            MessageRef subscriptionGone();

            @Name("Push not accepted")
            MessageRef pushNotAccepted();

            @Name("Key name length")
            MessageRef keyName(@Arg("most") int most);

            @Name("Ceremony from an older Steward")
            MessageRef ceremonyStale(@Arg("registration") boolean registration);

            @Name("Browser answer unreadable")
            MessageRef answerUnreadable();

            @Name("Ceremony for another account")
            MessageRef ceremonyOtherAccount(@Arg("registration") boolean registration);

            @Name("Key refused")
            MessageRef keyRefused(@Arg("registration") boolean registration, @Arg("why") String why);

            @Name("Key not accepted")
            MessageRef keyNotAccepted();
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

            @Name("A preview sent")
            MessageRef previewSent();

            @Name("No guild id")
            MessageRef noGuildId();

            @Name("The channels not published")
            MessageRef channelsNotPublished();

            @Name("The guild not listed")
            MessageRef guildNotListed();

            @Name("A service's own words")
            MessageRef words(@Arg("text") String text);
        }
    }

    /** What a setting holds, named when a typed value is not one. */
    enum Expected {
        INTEGER,
        DECIMAL,
        BOOLEAN
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
        COMPLETED,
        FAILED,
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
