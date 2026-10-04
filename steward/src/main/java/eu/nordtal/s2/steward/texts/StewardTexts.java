package eu.nordtal.s2.steward.texts;

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

            @Name("Agent not configured")
            MessageRef agentUnconfigured();
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
