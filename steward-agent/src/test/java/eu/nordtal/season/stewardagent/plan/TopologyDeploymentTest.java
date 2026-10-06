package eu.nordtal.season.stewardagent.plan;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.ComposeFile;
import eu.nordtal.season.common.Deployment;
import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.internalapi.BankWire;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.stewardagent.AgentApi;
import eu.nordtal.season.stewardagent.Compose;
import eu.nordtal.season.stewardagent.config.RunSpec;
import eu.nordtal.season.stewardagent.config.RunSpec.BackupSpec;
import eu.nordtal.season.stewardagent.topology.DeclaredTopology;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** The deployment, backup and standby half of {@link TopologyTest}, which reads compose.yml on its own. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopologyDeploymentTest {

    private static final List<Topology.Service> SERVERS =
            DeclaredTopology.topology().servers();

    /** The compose service running PostgreSQL, which every process with a login reaches. */
    private static final String DATABASE = "postgres";

    /** Every service that runs one of our jars on the image template; each writes the readiness marker. */
    private static final List<String> JVM_SERVICES =
            List.of(Topology.DISCORD_BOT, Topology.STEWARD, AgentWire.SERVICE, BankWire.SERVICE);

    private final ComposeFile compose = ComposeFile.get();

    @Test
    void noServiceOfOursRunsAJarFromAVolume() {
        // A jar in a volume outlives the image it came with, and nothing but the image may say which version runs.
        final String entrypoint = RepositoryRoot.read("deploy/jvm/entrypoint.sh");
        assertFalse(entrypoint.contains("JAR_DIR"), "deploy/jvm/entrypoint.sh picks a jar from a directory again");
        for (final String name : JVM_SERVICES) {
            final String mounts = String.valueOf(compose.service(name).mounts());
            assertFalse(
                    mounts.contains("/app/lib") || mounts.contains("/volumes/" + name),
                    "'" + name + "' mounts a volume where its own jar could lie: " + mounts);
        }
    }

    @Test
    void theAgentIsInEveryProfileSelectionBecauseEverythingElseDependsOnIt() {
        final ComposeFile.Service agent = compose.service(AgentWire.SERVICE);
        assertFalse(
                agent.has("profiles"),
                AgentWire.SERVICE + " has a profile again. It applies the schema and carries out every run, so a"
                        + " selection without it is a stack that cannot correctly start.");
        assertEquals(
                List.of("serve"),
                compose.service(Topology.STEWARD).list("command"),
                "the steward service must run `serve`; its other commands are asked for by name");
        assertTrue(
                agent.has("healthcheck"),
                "without the healthcheck, depends_on: service_healthy on every other service is a"
                        + " dependency on nothing");
    }

    @Test
    void everyProcessThatCanFailSilentlyReportsAReadinessMarkerToItsContainer() {
        // The marker decides readiness, not the port: a disabled plugin still leaves the port open.
        final List<String> named = new java.util.ArrayList<>(JVM_SERVICES);
        SERVERS.forEach(service -> named.add(service.name()));

        for (final String name : named) {
            final Map<String, Object> healthcheck = compose.service(name).block("healthcheck");
            assertFalse(
                    healthcheck.isEmpty(),
                    name + " has no healthcheck, so nothing outside its JVM"
                            + " reports anything about it - a container that is up, green by default, and"
                            + " running nothing useful");

            final String test = String.valueOf(healthcheck.get("test"));
            assertTrue(
                    test.contains("/tmp/nordtal-ready"),
                    name + "'s healthcheck does not look at the readiness marker: " + test);
            assertNotNull(
                    healthcheck.get("start_period"),
                    name + " has no start_period, so a"
                            + " perfectly healthy server reports unhealthy while it is still loading");
        }
    }

    @Test
    void theStalenessWindowInComposeYmlIsStillTheOneReadinessBeatsTo() {
        // compose.yml's shell test cannot read the Java constant, so the window is a second copy that can only drift.
        final java.util.regex.Pattern window = java.util.regex.Pattern.compile("-lt (\\d+)");
        final List<String> named = new java.util.ArrayList<>(JVM_SERVICES);
        SERVERS.forEach(service -> named.add(service.name()));

        for (final String name : named) {
            final Map<String, Object> healthcheck = compose.service(name).block("healthcheck");
            assertFalse(healthcheck.isEmpty(), name + " has no healthcheck at all - see the case above");
            final java.util.regex.Matcher matcher = window.matcher(String.valueOf(healthcheck.get("test")));

            assertTrue(
                    matcher.find(),
                    name + "'s healthcheck no longer compares the marker's age" + " against a window: "
                            + healthcheck.get("test"));
            assertEquals(
                    eu.nordtal.season.common.health.Readiness.STALE_AFTER.toSeconds(),
                    Long.parseLong(matcher.group(1)),
                    name + "'s healthcheck window and Readiness.STALE_AFTER disagree");
        }
    }

    @Test
    void theMinecraftServicesStillTestThePortAsWellAsTheMarker() {
        // The compose healthcheck replaces the image's own; the port check repeats because the marker lags readiness.
        for (final Topology.Service service : SERVERS) {
            final Map<String, Object> healthcheck =
                    compose.service(service.name()).block("healthcheck");
            assertFalse(healthcheck.isEmpty(), service.name() + " has no healthcheck at all - see above");
            final String test = String.valueOf(healthcheck.get("test"));

            assertTrue(
                    test.contains("/dev/tcp/"),
                    service.name() + " no longer connects to its own"
                            + " port, so a server that has stopped accepting players reports healthy: " + test);
            assertTrue(
                    test.contains("bash"),
                    service.name() + "'s healthcheck does not run under"
                            + " bash. /bin/sh in that image is dash, which has no /dev/tcp, so the port half"
                            + " would fail on every check: " + test);
        }
    }

    @Test
    void everyServiceThatLogsInToTheDatabaseWaitsForTheMigrateServiceToSucceed() {
        compose.services().forEach((name, service) -> {
            if (name.equals(Compose.MIGRATE)
                    || service.environment().keySet().stream().noneMatch(key -> key.endsWith("DATABASE_USERNAME"))) {
                return;
            }
            assertEquals(
                    Optional.of("service_completed_successfully"),
                    service.dependencyCondition(Compose.MIGRATE),
                    name + " logs in to the database without waiting for " + Compose.MIGRATE
                            + " to succeed, so it can come up against a schema older than itself");
        });
    }

    @Test
    void theMigrateServiceRunsOnceFromTheAgentsImageInEverySelection() {
        final ComposeFile.Service migrate = compose.service(Compose.MIGRATE);
        final ComposeFile.Service agent = compose.service(AgentWire.SERVICE);
        assertEquals(agent.text("image"), migrate.text("image"), "the schema is the agent's release's");
        assertEquals(List.of(Compose.MIGRATE), migrate.list("command"));
        // Restarted, an exited migration would run on a loop; completed, it is what everything waits for.
        assertEquals(Optional.of("no"), migrate.text("restart"));
        assertFalse(migrate.has("profiles"), "a selection without the migrate service starts nothing");
        assertFalse(
                agent.environment().keySet().stream().anyMatch(key -> key.matches(".*DATABASE_[A-Z_]+_PASSWORD")),
                AgentWire.SERVICE + " carries a role's password, which only the migrate service creates roles with");
    }

    @Test
    void everyVolumeABackupSavesIsAVolumeComposeYmlDeclaresPrefixIncluded() {
        // A volume's real name is the project name plus an underscore plus its key; a typo backs up an empty volume.
        final String project = project();
        final Set<String> declared = compose.block("volumes").keySet();

        for (final String volume : backupSet()) {
            assertTrue(
                    volume.startsWith(project + "_"),
                    "steward-agent mounts '" + volume + "' for the backup, which does not start with compose's"
                            + " own project name '" + project + "_'. Docker prefixes every volume in a"
                            + " compose project, and only the prefixed name exists.");
            final String key = volume.substring(project.length() + 1);
            assertTrue(
                    declared.contains(key),
                    "steward-agent mounts '" + volume + "' for the backup, but compose.yml declares no volume '" + key
                            + "'. This would snapshot a directory nothing writes and report success.");
        }
    }

    @Test
    void theBackupSavesBothWorldsAndNeverALiveDatabase() {
        final List<String> saved = backupSet();

        // Both worlds are hand-built and in no repository or release; a backup without one would still report DONE.
        assertTrue(saved.contains(project() + "_mc-smp"), "the backup does not save the world: " + saved);
        assertTrue(
                saved.contains(project() + "_mc-hunger-games"),
                "the backup does not save the hunger games world: " + saved);
        // A snapshot of a running PGDATA fails at RESTORE and nowhere else; the database is dumped instead.
        assertTrue(
                saved.stream().noneMatch(volume -> volume.endsWith("postgres-data")),
                "the backup tars a live database directory: " + saved);
    }

    @Test
    void aBackupStopsBothWorldsAndKeepsTheNetworkAndTheBotUp() {
        // A running Paper server's snapshot is torn; proxy, limbo and the bot hold no world to save.
        final List<String> stopped = new java.util.ArrayList<>();
        compose.services().forEach((name, service) -> {
            if ("stop".equals(service.labels().get("eu.nordtal.backup"))) {
                stopped.add(name);
            }
        });
        assertEquals(List.of(Topology.HUNGER_GAMES, Topology.SMP), stopped);
    }

    @Test
    void aRestoreCanWriteIntoEveryVolumeABackupSaves() {
        final String root = AgentApi.Paths.DEFAULTS.backupSources() + "/";
        for (final String mount : compose.service(AgentWire.SERVICE).mounts()) {
            if (destinationOf(mount).startsWith(root)) {
                // A restore unpacks into the same mount the backup reads, so a read-only one fails only then.
                assertFalse(mount.endsWith(":ro"), "steward-agent mounts " + destinationOf(mount) + " read-only");
            }
        }
    }

    /** The compose project name, which is the prefix Docker puts on every volume in it. */
    private String project() {
        return compose.projectName()
                .orElseThrow(() -> new AssertionError("compose.yml has no top-level name:, so the"
                        + " volume prefix is the directory name and depends on where somebody cloned this repository"));
    }

    /** The volumes a backup saves: every mount under steward-agent's backup sources, as the agent reads them. */
    private List<String> backupSet() {
        final String root = AgentApi.Paths.DEFAULTS.backupSources() + "/";
        final List<String> saved = new java.util.ArrayList<>();
        for (final String mount : compose.service(AgentWire.SERVICE).mounts()) {
            final String withoutMode =
                    mount.endsWith(":ro") || mount.endsWith(":rw") ? mount.substring(0, mount.lastIndexOf(':')) : mount;
            // Parsed from the right: a variable-backed default itself contains colons.
            final String destination = withoutMode.substring(withoutMode.lastIndexOf(':') + 1);
            if (destination.startsWith(root)) {
                saved.add(destination.substring(root.length()));
            }
        }
        assertFalse(saved.isEmpty(), "steward-agent mounts nothing under " + root + ", so a backup saves nothing");
        return saved;
    }

    @Test
    void everyServiceLogsInAsItsOwnRoleAndTheMigratorCarriesEveryRolesPassword() {
        // A username that is not the role's name logs in as nobody; a password the migrator lacks creates no role.
        final String composeText = compose.text();
        for (final eu.nordtal.season.database.DatabaseRole role : eu.nordtal.season.database.DatabaseRole.values()) {
            if (!role.hasPassword()) {
                continue;
            }
            assertTrue(
                    composeText.contains("DATABASE_USERNAME: " + role.roleName() + "\n"),
                    "no service in compose.yml logs in as " + role.roleName());
            assertTrue(
                    compose.service(Compose.MIGRATE)
                            .environment()
                            .containsKey(eu.nordtal.season.stewardagent.schema.Schema.passwordVariable(role)),
                    "the migrate service is not handed "
                            + eu.nordtal.season.stewardagent.schema.Schema.passwordVariable(role));
        }
    }

    @Test
    void theLocalEnvFileAnswersEveryVariableComposeYmlRequires() {
        // One unanswered `${X:?}` stops every service; comments are stripped since compose never interpolates them.
        final String uncommented = compose.text()
                .lines()
                .filter(line -> !line.strip().startsWith("#"))
                .collect(java.util.stream.Collectors.joining("\n"));
        final String env = RepositoryRoot.read("deploy/dev.env.example");

        final Set<String> defined = env.lines()
                .map(String::strip)
                .filter(line -> !line.startsWith("#"))
                .filter(line -> line.contains("="))
                .map(line -> line.substring(0, line.indexOf('=')))
                .collect(java.util.stream.Collectors.toSet());

        final java.util.regex.Matcher required =
                java.util.regex.Pattern.compile("\\$\\{([A-Z0-9_]+):\\?").matcher(uncommented);
        final List<String> missing = new java.util.ArrayList<>();
        while (required.find()) {
            if (!defined.contains(required.group(1))) {
                missing.add(required.group(1));
            }
        }

        assertEquals(
                List.of(),
                missing.stream().distinct().sorted().toList(),
                "deploy/dev.env.example does not answer every required variable in compose.yml."
                        + " `dev up` would fail on the first of them, naming one variable"
                        + " and no others, however many are missing.");
    }

    @Test
    void everyServiceNameStewardLooksUpIsAServiceComposeYmlDefines() {
        // A name the code uses that compose.yml lacks cannot be found or stopped; steward looks itself up too.
        final List<String> asked = List.of(
                Topology.PROXY,
                Topology.LIMBO,
                Topology.HUNGER_GAMES,
                Topology.SMP,
                Topology.DISCORD_BOT,
                Topology.STEWARD,
                Topology.MIGRATE);

        for (final String name : asked.stream().distinct().toList()) {
            assertTrue(
                    compose.services().containsKey(name),
                    "Topology looks the service name '" + name + "' up"
                            + " in the container runtime, but compose.yml defines no service called that."
                            + " The runtime is keyed by compose's own service names, so this one can never"
                            + " be found - and a service that cannot be found cannot be stopped, which"
                            + " fails the entire update run rather than just that line.");
        }
    }

    @Test
    void theBankKeyIsHandedToStewardBunqAndToNoOtherService() {
        // Only steward-bunq holds the bank client, so a key handed to any other container is a key it can leak.
        compose.services().forEach((name, service) -> {
            if (!name.equals(BankWire.SERVICE)) {
                final String environment = String.valueOf(service.environment());
                assertFalse(
                        environment.contains("BUNQ_API_KEY") || environment.contains("BUNQ_ACCOUNT_ID"),
                        "compose.yml hands the bunq key or account to '" + name + "', but only "
                                + BankWire.SERVICE + " speaks to the bank. Every other process reaches"
                                + " it through steward-bunq's API and has no use for the key.");
            }
        });
    }

    @Test
    void noServiceButStewardAndTheDatabaseSharesANetworkWithTheAgentOrTheBank() {
        // The agent holds the docker socket and steward-bunq the bank key; steward is the one caller of both.
        final Map<String, Set<String>> allowed = Map.of(
                AgentWire.SERVICE, Set.of(Topology.STEWARD, DATABASE, Compose.MIGRATE),
                BankWire.SERVICE, Set.of(Topology.STEWARD));
        allowed.forEach((guarded, partners) -> {
            final Set<String> theirs = compose.service(guarded).networks();
            compose.services().keySet().stream()
                    .filter(name -> !name.equals(guarded) && !partners.contains(name))
                    .forEach(name -> {
                        final Set<String> shared =
                                new LinkedHashSet<>(compose.service(name).networks());
                        shared.retainAll(theirs);
                        assertTrue(
                                shared.isEmpty(),
                                "'" + name + "' shares " + shared + " with " + guarded + ", which only "
                                        + partners + " may share a network with. Give the two a network of"
                                        + " their own instead.");
                    });
        });
    }

    @Test
    void theNetworksThatLeadToTheAgentOrTheBankHaveNoWayOut() {
        // An internal network has no gateway; only a network the guarded service has to itself may lead out.
        final Set<String> bank =
                new LinkedHashSet<>(compose.service(BankWire.SERVICE).networks());
        bank.retainAll(compose.service(Topology.STEWARD).networks());
        assertFalse(bank.isEmpty(), "steward shares no network with " + BankWire.SERVICE + ", so it cannot call it");
        final Set<String> agent =
                new LinkedHashSet<>(compose.service(AgentWire.SERVICE).networks());
        agent.retainAll(compose.service(Topology.STEWARD).networks());
        assertFalse(agent.isEmpty(), "steward shares no network with " + AgentWire.SERVICE + ", so it cannot call it");

        final Map<String, Object> declared = compose.block("networks");
        for (final String guarded : List.of(AgentWire.SERVICE, BankWire.SERVICE)) {
            for (final String network : compose.service(guarded).networks()) {
                final boolean shared = compose.services().keySet().stream()
                        .anyMatch(name -> !name.equals(guarded)
                                && compose.service(name).networks().contains(network));
                final Object definition = declared.get(network);
                assertTrue(
                        !shared || (definition instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("internal"))),
                        "The network '" + network + "' joins " + guarded + " to another service but is not"
                                + " `internal: true`, so it is also a way out to the internet and the host.");
            }
        }
    }

    /** {@link RunSpec} answering nothing but its own defaults. */
    private static RunSpec defaults() {
        return new RunSpec() {

            @Override
            public BackupSpec backup() {
                return new BackupSpec() {
                    // backup.retention has no default of its own, so this hands its defaults back by name.

                    @Override
                    public RetentionSpec retention() {
                        return new RetentionSpec() {};
                    }
                };
            }
        };
    }

    @Test
    void theDatabaseCanWriteItsDumpWhereTheAgentLaterLooksForIt() {
        // pg_dump runs inside postgres, so the agent's backup directory is a path in that container too.
        final BackupSpec backup = defaults().backup();
        final String directory = AgentApi.Paths.DEFAULTS.backups().toString();

        final String agent = writableMountAt(AgentWire.SERVICE, directory);
        final String database = writableMountAt(backup.databaseService(), directory);
        assertEquals(
                agent,
                database,
                backup.databaseService() + " writes the dump to "
                        + directory + " out of one volume and " + AgentWire.SERVICE + " reads " + directory
                        + " out of another, so the dump is saved where nothing ever looks for it.");
    }

    /** The volume behind a service's mount at {@code path}, insisting it is not read-only. */
    private String writableMountAt(final String service, final String path) {
        final String mount = compose.service(service).mounts().stream()
                .filter(each -> destinationOf(each).equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError(service + " mounts nothing at " + path
                        + ", and the nightly database dump" + " is written there by name."));
        assertFalse(mount.endsWith(":ro"), service + " mounts " + path + " read-only, and the dump is written to it.");
        return ComposeFile.sourceOf(mount);
    }

    /** The container path a mount lands on, whatever the source expression contains. */
    private static String destinationOf(final String mount) {
        final String withoutMode =
                mount.endsWith(":ro") || mount.endsWith(":rw") ? mount.substring(0, mount.lastIndexOf(':')) : mount;
        return withoutMode.substring(withoutMode.lastIndexOf(':') + 1);
    }

    @Test
    void composeYmlLeavesBootstrapToTheRunsGroup() {
        final Map<String, String> environment =
                compose.service(AgentWire.SERVICE).environment();

        assertAll(
                () -> assertNull(
                        environment.get("NORDTAL_STEWARD_AGENT_BOOTSTRAP"),
                        "compose.yml passes NORDTAL_STEWARD_AGENT_BOOTSTRAP again. A value in the host's"
                                + " environment wins over the runs group, so the setting cannot be"
                                + " changed from the interface."),
                () -> assertTrue(
                        defaults().bootstrap(),
                        "RunSpec#bootstrap is false. It is now the ONLY thing that makes a"
                                + " first deployment fill its empty volumes, so with it off nothing"
                                + " comes up without somebody asking for a run."));
    }

    /** Every image of ours, as compose.yml names it: the one prefix, the repository, then the release. */
    private static final java.util.regex.Pattern OURS = java.util.regex.Pattern.compile(
            "\\$\\{NORDTAL_IMAGES:-ghcr\\.io/nordtal}/([a-z-]+):\\$\\{NORDTAL_RELEASE:\\?[^}]+}");

    @Test
    void everyImageOfOursIsTheReleaseTheAgentRuns() {
        // A moving tag lets one service run another release than the agent that carries its schema.
        compose.services().forEach((name, service) -> {
            final String image = service.text("image").orElse("");
            if (image.contains("nordtal")) {
                assertTrue(
                        OURS.matcher(image).matches(),
                        "compose.yml's '" + name + "' names the image " + image + ". Every image of ours is"
                                + " ${NORDTAL_IMAGES:-ghcr.io/nordtal}/<name>:${NORDTAL_RELEASE:?...}, so the"
                                + " release steward-agent runs is the release of everything.");
            }
        });
    }

    @Test
    void theReleaseWorkflowPushesEveryImageComposeYmlNamesUnderTheVersionAlone() {
        // A deploy pulls and never builds, so an image the workflow does not push fails with `denied`.
        final String workflow = RepositoryRoot.read(".github/workflows/release.yml");
        assertFalse(workflow.contains(":latest"), "release.yml pushes a moving tag again");
        assertTrue(
                workflow.indexOf("Attach the artifacts") > workflow.lastIndexOf("docker/build-push-action"),
                "release.yml attaches the jars before every image is pushed, and a running agent hands an update"
                        + " over as soon as the jars are there");
        compose.services().forEach((name, service) -> {
            final java.util.regex.Matcher ours =
                    OURS.matcher(service.text("image").orElse(""));
            if (ours.matches()) {
                assertTrue(
                        workflow.contains("ghcr.io/nordtal/" + ours.group(1) + ":${{ env.VERSION }}"),
                        "compose.yml's '" + name + "' pulls ghcr.io/nordtal/" + ours.group(1)
                                + ":<release>, and .github/workflows/release.yml pushes no such tag.");
            }
        });
    }

    @Test
    void everyJvmServiceBuildsFromTheOneTemplateUnderItsOwnName() {
        final String template = RepositoryRoot.read("deploy/jvm/Dockerfile");
        final List<String> admitted =
                RepositoryRoot.read(".dockerignore").lines().map(String::strip).toList();
        compose.services().forEach((name, service) -> {
            if ("deploy/jvm/Dockerfile".equals(service.block("build").get("dockerfile"))) {
                assertTrue(
                        JVM_SERVICES.contains(name),
                        "'" + name + "' builds from the JVM template but is not in JVM_SERVICES, so nothing"
                                + " here holds its readiness marker.");
            }
        });
        assertAll(JVM_SERVICES.stream().map(name -> () -> {
            final Map<String, Object> map = compose.service(name).block("build");
            assertFalse(map.isEmpty(), "'" + name + "' has no build: block");
            assertEquals(
                    ".", map.get("context"), "'" + name + "' builds from another context than the repository root");
            assertEquals(
                    "deploy/jvm/Dockerfile", map.get("dockerfile"), "'" + name + "' builds from its own Dockerfile");
            assertEquals(
                    name,
                    ((Map<?, ?>) map.getOrDefault("args", Map.of())).get("MODULE"),
                    "'" + name + "' passes another MODULE, so its image runs another module's jar");
            assertTrue(
                    template.lines().anyMatch(line -> line.strip().equals("FROM jvm AS " + name)),
                    "deploy/jvm/Dockerfile has no stage '" + name + "', so its build fails on the last FROM.");
            assertTrue(
                    admitted.contains("!" + name + "/build/image/app.jar"),
                    "/.dockerignore does not let " + name + "'s jar in, so the image has nothing to COPY.");
        }));
    }

    @Test
    void theAgentDeploysTheProjectItWasStartedInNotOneOfItsOwn() {
        // A disagreeing `--project-name` fails nothing visibly: a second stack comes up beside the running one.
        final String declared = compose.service(AgentWire.SERVICE).environment().get("COMPOSE_PROJECT_NAME");
        assertEquals(
                "${COMPOSE_PROJECT_NAME:-" + Deployment.PROJECT + "}",
                declared,
                "compose.yml no longer hands steward-agent the project name, or its fallback is not"
                        + " Deployment.PROJECT, which the agent and steward fall back to. Two different"
                        + " defaults for the project name are two deployments of the same stack.");
    }

    @Test
    void packetEventsReallyIsRequiredBySmpWhichIsWhyTheTopologyListsIt() {
        // Checked against the manifest that enforces it rather than against a comment about it.
        final String manifest = "smp/src/main/resources/paper-plugin.yml";
        final String text = RepositoryRoot.read(manifest);

        assertTrue(text.contains("packetevents:"), manifest + " no longer names packetevents");
        assertTrue(text.contains("required: true"), manifest + " no longer requires it");
        assertTrue(
                smpPlugins().contains(Topology.PACKETEVENTS),
                "smp requires packetevents but its eu.nordtal.plugins label does not name it, so steward would"
                        + " never install it");
    }

    @Test
    void noMinecraftServiceExistsInComposeYmlThatTheTopologyDoesNotKnowAbout() {
        // Catches a backend added to compose.yml and not here, which steward would then quietly never touch.
        final Set<String> known = new LinkedHashSet<>();
        SERVERS.forEach(service -> known.add(service.name()));
        // The standbys are Minecraft services too, deliberately no servers: their jars are copied.
        known.addAll(DeclaredTopology.topology().standbys());

        compose.services().forEach((name, service) -> {
            if (service.environment().containsKey("SERVER_KIND")) {
                assertTrue(
                        known.contains(name),
                        "compose.yml runs a Minecraft service '" + name + "' that no label makes a server or a"
                                + " standby. Give it eu.nordtal.server and eu.nordtal.plugins - steward will not"
                                + " touch it otherwise.");
            }
        });
    }

    @Test
    void everyVariableTheMinecraftEntrypointReadsIsAVariableComposeYmlPassesIn() {
        // Written as "every knob" rather than one name, so a variable added later arrives already covered.
        final String entrypoint = RepositoryRoot.read("deploy/minecraft/entrypoint.sh");

        // `VAR="${VAR:-...}"` is how the entrypoint states a knob with a default; the quote is part of the shape.
        final java.util.Set<String> knobs = new LinkedHashSet<>();
        final java.util.regex.Matcher reads = java.util.regex.Pattern.compile(
                        "^([A-Z][A-Z0-9_]+)=\"?\\$\\{\\1:-", java.util.regex.Pattern.MULTILINE)
                .matcher(entrypoint);
        while (reads.find()) {
            knobs.add(reads.group(1));
        }
        assertTrue(
                knobs.contains("JVM_OPTS"),
                "the entrypoint no longer reads JVM_OPTS the way this test recognises a knob - " + knobs);

        // DATA and LEVEL_NAME are deliberately internal: a knob is wired into compose or listed here, never dropped.
        final java.util.Set<String> internal = java.util.Set.of("DATA", "LEVEL_NAME");
        knobs.removeAll(internal);

        final List<String> deaf = new java.util.ArrayList<>();
        compose.services().forEach((name, service) -> {
            final Map<String, String> environment = service.environment();
            if (!environment.containsKey("SERVER_KIND")) {
                return;
            }
            knobs.stream()
                    .filter(knob -> !environment.containsKey(knob))
                    .forEach(knob -> deaf.add(name + " ignores " + knob));
        });

        assertEquals(
                List.of(),
                deaf,
                "a Minecraft service that does not receive a variable its own entrypoint reads is a"
                        + " setting somebody can write and nothing can apply.");
    }

    /** The account a Dockerfile or one of its stages makes, as {@code useradd --uid U --gid G}. */
    private static final java.util.regex.Pattern ACCOUNT =
            java.util.regex.Pattern.compile("useradd --uid (\\d+) --gid (\\d+) ");

    /**
     * Every server and every JVM service but steward-agent runs as the uid its image makes, without a capability.
     *
     * The servers run third-party plugins; steward-agent alone keeps root, since the Docker socket it holds is root
     * on the host whatever its uid, and it hands every other service's volumes to that service's {@code user:}.
     */
    @Test
    void everyServiceOfOursButTheAgentRunsAsTheAccountItsImageMakesWithoutACapability() {
        final Map<String, String> confined = new java.util.LinkedHashMap<>();
        compose.services().forEach((name, service) -> {
            final Map<String, Object> build = service.block("build");
            if ("./deploy/minecraft".equals(build.get("context"))) {
                confined.put(name, RepositoryRoot.read("deploy/minecraft/Dockerfile"));
            } else if ("deploy/jvm/Dockerfile".equals(build.get("dockerfile")) && !AgentWire.SERVICE.equals(name)) {
                confined.put(name, stage(RepositoryRoot.read("deploy/jvm/Dockerfile"), name));
            }
        });
        assertTrue(
                confined.keySet().containsAll(List.of(Topology.DISCORD_BOT, Topology.STEWARD, BankWire.SERVICE)),
                "a JVM service lost its build block, so nothing here holds its user: " + confined.keySet());
        SERVERS.forEach(server ->
                assertTrue(confined.containsKey(server.name()), server.name() + " is not built from deploy/minecraft"));

        assertAll(confined.entrySet().stream().map(entry -> () -> {
            final String name = entry.getKey();
            final ComposeFile.Service service = compose.service(name);
            final java.util.regex.Matcher account = ACCOUNT.matcher(entry.getValue());
            assertTrue(account.find(), "the image of '" + name + "' makes no account with a fixed uid and gid");
            assertTrue(
                    entry.getValue().lines().anyMatch(line -> line.startsWith("USER ")),
                    "the image of '" + name + "' never switches to its account");
            assertEquals(
                    Optional.of(account.group(1) + ":" + account.group(2)),
                    service.text("user"),
                    "'" + name + "' must run as the account its image makes; steward-agent hands its volumes to"
                            + " this user: before it starts");
            assertEquals(List.of("ALL"), service.list("cap_drop"), "'" + name + "' keeps capabilities");
            assertTrue(
                    service.list("security_opt").contains("no-new-privileges:true"),
                    "'" + name + "' could gain privileges through a setuid binary");
        }));
    }

    @Test
    void noContainerIsGivenAWayToTheHost() {
        compose.services()
                .forEach((name, service) -> assertFalse(
                        service.has("extra_hosts"),
                        "'" + name + "' maps a name to the host again: " + service.list("extra_hosts")));
    }

    /** The lines of one {@code FROM jvm AS name} stage, up to the next {@code FROM}. */
    private static String stage(final String dockerfile, final String name) {
        final StringBuilder lines = new StringBuilder();
        boolean inside = false;
        for (final String line : dockerfile.lines().toList()) {
            if (line.startsWith("FROM ")) {
                inside = line.strip().equals("FROM jvm AS " + name);
            }
            if (inside) {
                lines.append(line).append('\n');
            }
        }
        return lines.toString();
    }

    private static java.util.List<String> smpPlugins() {
        return SERVERS.stream()
                .filter(service -> service.name().equals(Topology.SMP))
                .findFirst()
                .orElseThrow()
                .plugins();
    }
}
