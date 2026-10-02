package eu.nordtal.s2.stewardagent.plan;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.Deployment;
import eu.nordtal.s2.internalapi.BankWire;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.Topology;
import eu.nordtal.s2.stewardagent.AgentApi;
import eu.nordtal.s2.stewardagent.config.RunSpec;
import eu.nordtal.s2.stewardagent.config.RunSpec.BackupSpec;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.yaml.snakeyaml.Yaml;

/** The deployment, backup and standby half of {@link TopologyTest}, which reads compose.yml on its own. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopologyDeploymentTest {

    /** The compose service running PostgreSQL, which every process with a login reaches. */
    private static final String DATABASE = "postgres";

    /** Every service that runs one of our jars on the image template; each writes the readiness marker. */
    private static final List<String> JVM_SERVICES =
            List.of(Topology.DISCORD_BOT, Topology.STEWARD, AgentWire.SERVICE, BankWire.SERVICE);

    private final Map<String, Object> services = readComposeServices();

    @Test
    void noServiceOfOursRunsAJarFromAVolume() throws IOException {
        // A jar in a volume outlives the image it came with, and nothing but the image may say which version runs.
        final String entrypoint = Files.readString(findUpwards("deploy/jvm/entrypoint.sh"), StandardCharsets.UTF_8);
        assertFalse(entrypoint.contains("JAR_DIR"), "deploy/jvm/entrypoint.sh picks a jar from a directory again");
        for (final String name : JVM_SERVICES) {
            final String mounts = String.valueOf(((Map<?, ?>) services.get(name)).get("volumes"));
            assertFalse(
                    mounts.contains("/app/lib") || mounts.contains("/volumes/" + name),
                    "'" + name + "' mounts a volume where its own jar could lie: " + mounts);
        }
    }

    @Test
    void theAgentIsInEveryProfileSelectionBecauseEverythingElseDependsOnIt() {
        @SuppressWarnings("unchecked")
        final Map<String, Object> agent = (Map<String, Object>) services.get(AgentWire.SERVICE);
        assertFalse(
                agent.containsKey("profiles"),
                AgentWire.SERVICE + " has a profile again. It applies the schema and carries out every run, so a"
                        + " selection without it is a stack that cannot correctly start.");
        @SuppressWarnings("unchecked")
        final Map<String, Object> steward = (Map<String, Object>) services.get(Topology.STEWARD);
        assertEquals(
                List.of("serve"),
                steward.get("command"),
                "the steward service must run `serve`; its other commands are asked for by name");
        assertNotNull(
                agent.get("healthcheck"),
                "without the healthcheck, depends_on: service_healthy on every other service is a"
                        + " dependency on nothing");
    }

    @Test
    void everyProcessThatCanFailSilentlyReportsAReadinessMarkerToItsContainer() {
        // The marker decides readiness, not the port: a disabled plugin still leaves the port open.
        final List<String> named = new java.util.ArrayList<>(JVM_SERVICES);
        Topology.SERVICES.forEach(service -> named.add(service.name()));

        for (final String name : named) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> service = (Map<String, Object>) services.get(name);
            assertNotNull(service, "compose.yml has no service '" + name + "'");

            @SuppressWarnings("unchecked")
            final Map<String, Object> healthcheck = (Map<String, Object>) service.get("healthcheck");
            assertNotNull(
                    healthcheck,
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
        Topology.SERVICES.forEach(service -> named.add(service.name()));

        for (final String name : named) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> service = (Map<String, Object>) services.get(name);
            @SuppressWarnings("unchecked")
            final Map<String, Object> healthcheck = (Map<String, Object>) service.get("healthcheck");
            assertNotNull(healthcheck, name + " has no healthcheck at all - see the case above");
            final java.util.regex.Matcher matcher = window.matcher(String.valueOf(healthcheck.get("test")));

            assertTrue(
                    matcher.find(),
                    name + "'s healthcheck no longer compares the marker's age" + " against a window: "
                            + healthcheck.get("test"));
            assertEquals(
                    eu.nordtal.s2.common.health.Readiness.STALE_AFTER.toSeconds(),
                    Long.parseLong(matcher.group(1)),
                    name + "'s healthcheck window and Readiness.STALE_AFTER disagree");
        }
    }

    @Test
    void theMinecraftServicesStillTestThePortAsWellAsTheMarker() {
        // The compose healthcheck replaces the image's own; the port check repeats because the marker lags readiness.
        for (final Topology.Service service : Topology.SERVICES) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> defined = (Map<String, Object>) services.get(service.name());
            @SuppressWarnings("unchecked")
            final Map<String, Object> healthcheck = (Map<String, Object>) defined.get("healthcheck");
            assertNotNull(healthcheck, service.name() + " has no healthcheck at all - see above");
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
    void everyServiceThatReadsTheDatabaseWaitsForTheSchema() {
        services.forEach((name, definition) -> {
            @SuppressWarnings("unchecked")
            final Map<String, Object> service = (Map<String, Object>) definition;
            // steward-agent applies the schema; postgres, pack-host, steward-bunq and caddy read no rows.
            if (name.equals("postgres")
                    || name.equals("pack-host")
                    || name.equals("steward-agent")
                    || name.equals("steward-bunq")
                    || name.equals("caddy")) {
                return;
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> dependsOn = (Map<String, Object>) service.get("depends_on");
            assertNotNull(
                    dependsOn,
                    name + " does not wait for " + AgentWire.SERVICE + ", so it can come up"
                            + " against a schema older than itself after a redeploy");
            assertTrue(
                    String.valueOf(dependsOn).contains("service_healthy"),
                    name + " depends on " + AgentWire.SERVICE + " but not on it being healthy, which waits for"
                            + " the container to exist rather than for the schema to be current");
        });
    }

    @Test
    void everyVolumeABackupSavesIsAVolumeComposeYmlDeclaresPrefixIncluded() {
        // A volume's real name is the project name plus an underscore plus its key; a typo backs up an empty volume.
        final String project = composeProject();
        final Set<String> declared = composeVolumes();

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
    void theBackupSavesTheWorldAndNeverALiveDatabase() {
        final List<String> saved = backupSet();

        // Nordtal is a hand-built world in no repository or release; a backup without it would still report DONE.
        assertTrue(saved.contains(composeProject() + "_mc-smp"), "the backup does not save the world: " + saved);
        // A snapshot of a running PGDATA fails at RESTORE and nowhere else; the database is dumped instead.
        assertTrue(
                saved.stream().noneMatch(volume -> volume.endsWith("postgres-data")),
                "the backup tars a live database directory: " + saved);
    }

    @Test
    void aBackupStopsTheWorldAndTheBotAndKeepsTheNetworkUp() {
        // A snapshot of a running Paper server is torn; proxy, limbo and hunger-games hold no world worth saving.
        final List<String> stopped = new java.util.ArrayList<>();
        for (final Map.Entry<String, Object> entry : services.entrySet()) {
            @SuppressWarnings("unchecked")
            final Object labels = ((Map<String, Object>) entry.getValue()).get("labels");
            if (labels instanceof Map<?, ?> map && "stop".equals(String.valueOf(map.get("eu.nordtal.backup")))) {
                stopped.add(entry.getKey());
            }
        }
        assertEquals(
                List.of(Topology.DISCORD_BOT, Topology.SMP),
                stopped.stream().sorted().toList());
    }

    @Test
    void aRestoreCanWriteIntoEveryVolumeABackupSaves() {
        @SuppressWarnings("unchecked")
        final Map<String, Object> agent = (Map<String, Object>) services.get(AgentWire.SERVICE);
        final String root = AgentApi.Paths.DEFAULTS.backupSources() + "/";
        for (final String mount : mountsOf(agent)) {
            if (destinationOf(mount).startsWith(root)) {
                // A restore unpacks into the same mount the backup reads, so a read-only one fails only then.
                assertFalse(mount.endsWith(":ro"), "steward-agent mounts " + destinationOf(mount) + " read-only");
            }
        }
    }

    /** The volumes a backup saves: every mount under steward-agent's backup sources, as the agent reads them. */
    private List<String> backupSet() {
        @SuppressWarnings("unchecked")
        final Map<String, Object> agent = (Map<String, Object>) services.get(AgentWire.SERVICE);
        final String root = AgentApi.Paths.DEFAULTS.backupSources() + "/";
        final List<String> saved = new java.util.ArrayList<>();
        for (final String mount : mountsOf(agent)) {
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
    void everyServiceLogsInAsItsOwnRoleAndTheMigratorCarriesEveryRolesPassword() throws IOException {
        // A username that is not the role's name logs in as nobody; a password steward lacks creates no role.
        final String compose = Files.readString(findUpwards("compose.yml"), StandardCharsets.UTF_8);
        for (final eu.nordtal.s2.database.DatabaseRole role : eu.nordtal.s2.database.DatabaseRole.values()) {
            if (!role.hasPassword()) {
                continue;
            }
            // Steward logs in as the owner while it is the one that migrates; its role holds the grants for after.
            assertTrue(
                    role == eu.nordtal.s2.database.DatabaseRole.STEWARD
                            || compose.contains("DATABASE_USERNAME: " + role.roleName() + "\n"),
                    "no service in compose.yml logs in as " + role.roleName());
            assertTrue(
                    compose.contains(eu.nordtal.s2.stewardagent.schema.Schema.passwordVariable(role) + ": "),
                    "steward is not handed " + eu.nordtal.s2.stewardagent.schema.Schema.passwordVariable(role));
        }
    }

    @Test
    void theLocalEnvFileAnswersEveryVariableComposeYmlRequires() throws IOException {
        // One unanswered `${X:?}` stops every service; comments are stripped since compose never interpolates them.
        final String compose = Files.readString(findUpwards("compose.yml"), StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.strip().startsWith("#"))
                .collect(java.util.stream.Collectors.joining("\n"));
        final String env = Files.readString(findUpwards("deploy/dev.env.example"), StandardCharsets.UTF_8);

        final Set<String> defined = env.lines()
                .map(String::strip)
                .filter(line -> !line.startsWith("#"))
                .filter(line -> line.contains("="))
                .map(line -> line.substring(0, line.indexOf('=')))
                .collect(java.util.stream.Collectors.toSet());

        final java.util.regex.Matcher required =
                java.util.regex.Pattern.compile("\\$\\{([A-Z0-9_]+):\\?").matcher(compose);
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
        // A name Topology uses that compose.yml lacks cannot be found or stopped; steward looks itself up too.
        final List<String> asked = new java.util.ArrayList<>();
        Topology.SERVICES.forEach(service -> asked.add(service.name()));
        asked.addAll(List.of(Topology.DISCORD_BOT, Topology.STEWARD));

        for (final String name : asked.stream().distinct().toList()) {
            assertNotNull(
                    services.get(name),
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
        services.forEach((name, service) -> {
            if (!name.equals(BankWire.SERVICE)) {
                final String environment = String.valueOf(((Map<?, ?>) service).get("environment"));
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
                AgentWire.SERVICE, Set.of(Topology.STEWARD, DATABASE),
                BankWire.SERVICE, Set.of(Topology.STEWARD));
        allowed.forEach((guarded, partners) -> {
            final Set<String> theirs = networksOf(guarded);
            services.keySet().stream()
                    .filter(name -> !name.equals(guarded) && !partners.contains(name))
                    .forEach(name -> {
                        final Set<String> shared = new LinkedHashSet<>(networksOf(name));
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
        final Set<String> bank = new LinkedHashSet<>(networksOf(BankWire.SERVICE));
        bank.retainAll(networksOf(Topology.STEWARD));
        assertFalse(bank.isEmpty(), "steward shares no network with " + BankWire.SERVICE + ", so it cannot call it");
        final Set<String> agent = new LinkedHashSet<>(networksOf(AgentWire.SERVICE));
        agent.retainAll(networksOf(Topology.STEWARD));
        assertFalse(agent.isEmpty(), "steward shares no network with " + AgentWire.SERVICE + ", so it cannot call it");

        final Map<?, ?> declared = (Map<?, ?>) composeRoot().get("networks");
        for (final String guarded : List.of(AgentWire.SERVICE, BankWire.SERVICE)) {
            for (final String network : networksOf(guarded)) {
                final boolean shared = services.keySet().stream()
                        .anyMatch(name ->
                                !name.equals(guarded) && networksOf(name).contains(network));
                final Object definition = declared.get(network);
                assertTrue(
                        !shared || (definition instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("internal"))),
                        "The network '" + network + "' joins " + guarded + " to another service but is not"
                                + " `internal: true`, so it is also a way out to the internet and the host.");
            }
        }
    }

    /** The networks a service joins; a service that names none is on {@code default}. */
    private Set<String> networksOf(final String service) {
        final Object networks = ((Map<?, ?>) services.get(service)).get("networks");
        if (networks == null) {
            return Set.of("default");
        }
        if (networks instanceof Map<?, ?> map) {
            return map.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
        }
        return ((List<?>) networks).stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
    }

    /** The compose project name, which is the prefix Docker puts on every volume in it. */
    private static String composeProject() {
        final Object name = composeRoot().get("name");
        assertNotNull(
                name,
                "compose.yml has no top-level name:, so the volume prefix is the"
                        + " directory name and depends on where somebody cloned this repository");
        return String.valueOf(name);
    }

    /** The keys under compose.yml's top-level {@code volumes:} block. */
    private static Set<String> composeVolumes() {
        final Map<?, ?> volumes = (Map<?, ?>) composeRoot().get("volumes");
        assertNotNull(volumes, "compose.yml has no volumes block");
        return volumes.keySet().stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /** {@link RunSpec} answering nothing but its own defaults. */
    private static RunSpec defaults() {
        return new RunSpec() {

            @Override
            public BackupSpec backup() {
                return new BackupSpec() {
                    // backup.remote has no default of its own, so this hands its defaults back by name.

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
        @SuppressWarnings("unchecked")
        final Map<String, Object> definition = (Map<String, Object>) services.get(service);
        assertNotNull(definition, "compose.yml has no service '" + service + "'");
        final String mount = mountsOf(definition).stream()
                .filter(each -> destinationOf(each).equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError(service + " mounts nothing at " + path
                        + ", and the nightly database dump" + " is written there by name."));
        assertFalse(mount.endsWith(":ro"), service + " mounts " + path + " read-only, and the dump is written to it.");
        return sourceOf(mount);
    }

    /** The container path a mount lands on, whatever the source expression contains. */
    private static String destinationOf(final String mount) {
        final String withoutMode =
                mount.endsWith(":ro") || mount.endsWith(":rw") ? mount.substring(0, mount.lastIndexOf(':')) : mount;
        return withoutMode.substring(withoutMode.lastIndexOf(':') + 1);
    }

    @Test
    void composeYmlLeavesBootstrapToTheRunsGroup() {
        @SuppressWarnings("unchecked")
        final Map<String, Object> agent = (Map<String, Object>) services.get(AgentWire.SERVICE);
        @SuppressWarnings("unchecked")
        final Map<String, Object> environment = (Map<String, Object>) agent.get("environment");

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
        services.forEach((name, definition) -> {
            final String image = String.valueOf(((Map<?, ?>) definition).get("image"));
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
    void theReleaseWorkflowPushesEveryImageComposeYmlNamesUnderTheVersionAlone() throws IOException {
        // A deploy pulls and never builds, so an image the workflow does not push fails with `denied`.
        final String workflow = Files.readString(findUpwards(".github/workflows/release.yml"), StandardCharsets.UTF_8);
        assertFalse(workflow.contains(":latest"), "release.yml pushes a moving tag again");
        assertTrue(
                workflow.indexOf("Attach the artifacts") > workflow.lastIndexOf("docker/build-push-action"),
                "release.yml attaches the jars before every image is pushed, and a running agent hands an update"
                        + " over as soon as the jars are there");
        services.forEach((name, definition) -> {
            final java.util.regex.Matcher ours = OURS.matcher(String.valueOf(((Map<?, ?>) definition).get("image")));
            if (ours.matches()) {
                assertTrue(
                        workflow.contains("ghcr.io/nordtal/" + ours.group(1) + ":${{ env.VERSION }}"),
                        "compose.yml's '" + name + "' pulls ghcr.io/nordtal/" + ours.group(1)
                                + ":<release>, and .github/workflows/release.yml pushes no such tag.");
            }
        });
    }

    @Test
    void everyJvmServiceBuildsFromTheOneTemplateUnderItsOwnName() throws IOException {
        final String template = Files.readString(findUpwards("deploy/jvm/Dockerfile"), StandardCharsets.UTF_8);
        final List<String> admitted = Files.readAllLines(findUpwards(".dockerignore"), StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .toList();
        services.forEach((name, definition) -> {
            final Object build = ((Map<?, ?>) definition).get("build");
            if (build instanceof Map<?, ?> map && "deploy/jvm/Dockerfile".equals(map.get("dockerfile"))) {
                assertTrue(
                        JVM_SERVICES.contains(name),
                        "'" + name + "' builds from the JVM template but is not in JVM_SERVICES, so nothing"
                                + " here holds its readiness marker.");
            }
        });
        assertAll(JVM_SERVICES.stream().map(name -> () -> {
            final Object build = ((Map<?, ?>) services.get(name)).get("build");
            assertTrue(build instanceof Map<?, ?>, "'" + name + "' has no build: block");
            final Map<?, ?> map = (Map<?, ?>) build;
            assertEquals(
                    ".", map.get("context"), "'" + name + "' builds from another context than the repository root");
            assertEquals(
                    "deploy/jvm/Dockerfile", map.get("dockerfile"), "'" + name + "' builds from its own Dockerfile");
            final Object args = map.get("args");
            assertEquals(
                    name,
                    args instanceof Map<?, ?> given ? given.get("MODULE") : null,
                    "'" + name + "' passes another MODULE, so its image runs another module's jar");
            assertTrue(
                    template.lines().anyMatch(line -> line.strip().equals("FROM jvm AS " + name)),
                    "deploy/jvm/Dockerfile has no stage '" + name + "', so its build fails on the last FROM.");
            assertTrue(
                    admitted.contains("!" + name + "/build/libs/*.jar"),
                    "/.dockerignore does not let " + name + "'s jar in, so the image has nothing to COPY.");
        }));
    }

    @Test
    void theAgentDeploysTheProjectItWasStartedInNotOneOfItsOwn() {
        // A disagreeing `--project-name` fails nothing visibly: a second stack comes up beside the running one.
        @SuppressWarnings("unchecked")
        final Map<String, Object> agent = (Map<String, Object>) services.get("steward-agent");
        @SuppressWarnings("unchecked")
        final Map<String, Object> environment = (Map<String, Object>) agent.get("environment");
        final String declared = String.valueOf(environment.get("COMPOSE_PROJECT_NAME"));
        assertEquals(
                "${COMPOSE_PROJECT_NAME:-" + Deployment.PROJECT + "}",
                declared,
                "compose.yml no longer hands steward-agent the project name, or its fallback is not"
                        + " Deployment.PROJECT, which the agent and steward fall back to. Two different"
                        + " defaults for the project name are two deployments of the same stack.");
    }

    @Test
    void displaytagsReallyIsRequiredBySmpWhichIsWhyTheTopologyListsIt() throws IOException {
        // Checked against the manifest that enforces it rather than against a comment about it.
        final Path manifest = findUpwards("smp/src/main/resources/paper-plugin.yml");
        final String text = Files.readString(manifest, StandardCharsets.UTF_8);

        assertTrue(text.contains("DisplayTags"), manifest + " no longer names DisplayTags");
        assertTrue(text.contains("required: true"), manifest + " no longer requires it");
        assertTrue(
                smpPlugins().contains(Topology.DISPLAY_TAGS),
                "smp requires DisplayTags but Topology does not list it, so steward would" + " never install it");
    }

    @Test
    void noMinecraftServiceExistsInComposeYmlThatTheTopologyDoesNotKnowAbout() {
        // Catches a backend added to compose.yml and not here, which steward would then quietly never touch.
        final Set<String> known = new LinkedHashSet<>();
        Topology.SERVICES.forEach(service -> known.add(service.name()));
        // The standbys are Minecraft services too, deliberately not Topology.Service rows: their jars are copied.
        known.addAll(Topology.standbyNames());

        services.forEach((name, definition) -> {
            @SuppressWarnings("unchecked")
            final Map<String, Object> environment =
                    (Map<String, Object>) ((Map<String, Object>) definition).get("environment");
            if (environment != null && environment.containsKey("SERVER_KIND")) {
                assertTrue(
                        known.contains(name),
                        "compose.yml runs a Minecraft service '" + name + "' that Topology does not know."
                                + " Add it to Topology.SERVICES - steward will not touch it otherwise.");
            }
        });
    }

    @Test
    void everyVariableTheMinecraftEntrypointReadsIsAVariableComposeYmlPassesIn() throws IOException {
        // Written as "every knob" rather than one name, so a variable added later arrives already covered.
        final String entrypoint =
                Files.readString(findUpwards("deploy/minecraft/entrypoint.sh"), StandardCharsets.UTF_8);

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
        services.forEach((name, definition) -> {
            @SuppressWarnings("unchecked")
            final Map<String, Object> environment =
                    (Map<String, Object>) ((Map<String, Object>) definition).get("environment");
            if (environment == null || !environment.containsKey("SERVER_KIND")) {
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

    /** The host side of a compose mount, everything before the last colon-separated field pair. */
    private static String sourceOf(final String mount) {
        final int split = mount.lastIndexOf(':');
        return mount.substring(0, split);
    }

    @SuppressWarnings("unchecked")
    private static List<String> mountsOf(final Map<String, Object> service) {
        final Object volumes = service.get("volumes");
        assertNotNull(volumes, "service has no volumes block");
        return ((List<Object>) volumes).stream().map(String::valueOf).toList();
    }

    private static java.util.List<String> smpPlugins() {
        return Topology.SERVICES.stream()
                .filter(service -> service.name().equals(Topology.SMP))
                .findFirst()
                .orElseThrow()
                .plugins();
    }

    private static Map<String, Object> readComposeServices() {
        @SuppressWarnings("unchecked")
        final Map<String, Object> services = (Map<String, Object>) composeRoot().get("services");
        assertNotNull(services, "compose.yml has no services block");
        return services;
    }

    /** compose.yml as SnakeYAML reads it, anchors and merge keys resolved. */
    private static Map<String, Object> composeRoot() {
        final Path compose = findUpwards("compose.yml");
        try (Reader reader = Files.newBufferedReader(compose, StandardCharsets.UTF_8)) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> root = (Map<String, Object>) new Yaml().load(reader);
            return root;
        } catch (final IOException unreadable) {
            throw new IllegalStateException("could not read " + compose, unreadable);
        }
    }

    private static Path findUpwards(final String relative) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            final Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "could not find " + relative + " above " + Path.of("").toAbsolutePath());
    }
}
