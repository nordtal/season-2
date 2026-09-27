package eu.nordtal.s2.steward.worker.plan;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.steward.worker.config.BackupSpec;
import eu.nordtal.s2.steward.worker.config.StewardSpec;
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

/**
 * The deployment, backup and standby half of {@link TopologyTest}, split out to keep both files under the line limit.
 *
 * It reads compose.yml itself rather than sharing TopologyTest's fixture, because the two run as separate classes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopologyDeploymentTest {

    private final Map<String, Object> services = readComposeServices();

    @Test
    void theBotsAndTheWorkersOwnVolumesAreMountedTooOrNeitherCouldBeUpdated() {
        @SuppressWarnings("unchecked")
        final Map<String, Object> worker = (Map<String, Object>) services.get("steward-worker");
        final String mounts = String.valueOf(worker.get("volumes"));

        for (final String artifact : Topology.STANDALONE_JARS) {
            assertTrue(
                    mounts.contains("/volumes/" + artifact),
                    "the worker does not mount /volumes/" + artifact + ", so it could never move"
                            + " that jar - which is the whole reason both stopped being images");
        }
    }

    @Test
    void theWorkerIsInEveryProfileSelectionBecauseEverythingElseDependsOnIt() {
        @SuppressWarnings("unchecked")
        final Map<String, Object> worker = (Map<String, Object>) services.get("steward-worker");
        assertFalse(
                worker.containsKey("profiles"),
                "the worker has a profile again. It applies the schema and answers /update, so a"
                        + " selection without it is a stack that cannot correctly start.");
        assertEquals(
                List.of("serve"),
                worker.get("command"),
                "the compose service must run `serve`; every writing mode is asked for by name");
        assertNotNull(
                worker.get("healthcheck"),
                "without the healthcheck, depends_on: service_healthy on every other service is a"
                        + " dependency on nothing");
    }

    @Test
    void everyProcessThatCanFailSilentlyReportsAReadinessMarkerToItsContainer() {
        // The marker decides readiness, not the port: a disabled plugin still leaves the port open.
        final List<String> named = new java.util.ArrayList<>(List.of(Topology.DISCORD_BOT));
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
        final List<String> named = new java.util.ArrayList<>(List.of(Topology.DISCORD_BOT));
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
            // postgres, pack-host, steward-deployer and caddy read no rows, so a depends_on there adds nothing.
            if (name.equals("steward-worker")
                    || name.equals("postgres")
                    || name.equals("pack-host")
                    || name.equals("steward-deployer")
                    || name.equals("caddy")) {
                return;
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> dependsOn = (Map<String, Object>) service.get("depends_on");
            assertNotNull(
                    dependsOn,
                    name + " does not wait for steward-worker, so it can come up"
                            + " against a schema older than itself after a redeploy");
            assertTrue(
                    String.valueOf(dependsOn).contains("service_healthy"),
                    name + " depends on steward-worker but not on it being healthy, which waits for"
                            + " the container to exist rather than for the schema to be current");
        });
    }

    @Test
    void everyVolumeABackupSavesIsAVolumeComposeYmlDeclaresPrefixIncluded() {
        // A volume's real name is the project name plus an underscore plus its key; a typo backs up an empty volume.
        final String project = composeProject();
        final Set<String> declared = composeVolumes();

        for (final String volume : defaults().backup().volumes()) {
            assertTrue(
                    volume.startsWith(project + "_"),
                    "backup.volumes lists '" + volume + "', which does not start with compose's own"
                            + " project name '" + project + "_'. Docker prefixes every volume in a"
                            + " compose project, and only the prefixed name exists.");
            final String key = volume.substring(project.length() + 1);
            assertTrue(
                    declared.contains(key),
                    "backup.volumes lists '" + volume + "', but compose.yml declares no volume '"
                            + key + "'. Docker creates a volume it has never seen on first use, so"
                            + " this would snapshot an empty directory and report success.");
        }
    }

    @Test
    void everyVolumeMountedForTheBackupIsAVolumeTheBackupActuallySaves() {
        // The quiet direction: a volume mounted for the backup but never named in backup.volumes is simply never saved.
        final Set<String> saved = Set.copyOf(defaults().backup().volumes());
        @SuppressWarnings("unchecked")
        final Map<String, Object> worker = (Map<String, Object>) services.get("steward-worker");
        final String root = new BackupSpec() {
                    // backup.remote has no default of its own, so this hands its defaults back by name.
                    @Override
                    public RemoteSpec remote() {
                        return new RemoteSpec() {};
                    }

                    @Override
                    public RetentionSpec retention() {
                        return new RetentionSpec() {};
                    }
                }.sourcesRoot()
                + "/";

        // Parsed from the right: a variable-backed default itself contains colons, so the left finds only part of it.
        int checked = 0;
        for (final String mount : mountsOf(worker)) {
            final String withoutMode =
                    mount.endsWith(":ro") || mount.endsWith(":rw") ? mount.substring(0, mount.lastIndexOf(':')) : mount;
            final String destination = withoutMode.substring(withoutMode.lastIndexOf(':') + 1);
            if (!destination.startsWith(root)) {
                continue;
            }
            checked++;
            final String volume = destination.substring(root.length());
            assertTrue(
                    saved.contains(volume),
                    "compose.yml mounts " + volume + " at " + destination + " for the backup to"
                            + " read, and backup.volumes does not list it. Nothing fails: the volume"
                            + " is simply never saved, and the report says nothing about a volume it"
                            + " was never asked for.");
        }
        // Counted too, because a loop that silently skips mounts asserts nothing about the ones it drops.
        final long mounted =
                mountsOf(worker).stream().filter(mount -> mount.contains(root)).count();
        assertEquals(
                mounted,
                checked,
                "compose.yml has " + mounted + " mounts under " + root
                        + " and this test looked at " + checked + " of them. The parsing dropped the rest,"
                        + " which is how a volume goes unsaved with a green build.");
    }

    @Test
    void everyServiceABackupStopsIsAServiceComposeYmlRuns() {
        // A name no container carries aborts the run before anything is saved, which is an outage for nothing.
        for (final String service : defaults().backup().stopServices()) {
            assertNotNull(
                    services.get(service),
                    "backup.stop-services names '" + service
                            + "', which is not a service in compose.yml. The run would stop nothing, save"
                            + " nothing and report a failure.");
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
    void everyServiceNameTheWorkerLooksUpIsAServiceComposeYmlDefines() {
        // A name Topology uses that compose.yml lacks cannot be found or stopped; the worker looks itself up too.
        final List<String> asked = new java.util.ArrayList<>();
        Topology.SERVICES.forEach(service -> asked.add(service.name()));
        asked.addAll(Topology.STANDALONE_JARS);

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

    /** The compose project name, which is the prefix Docker puts on every volume in it. */
    private static String composeProject() {
        final Path compose = findUpwards("compose.yml");
        try (Reader reader = Files.newBufferedReader(compose, StandardCharsets.UTF_8)) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> root = (Map<String, Object>) new Yaml().load(reader);
            final Object name = root.get("name");
            assertNotNull(
                    name,
                    "compose.yml has no top-level name:, so the volume prefix is the"
                            + " directory name and depends on where somebody cloned this repository");
            return String.valueOf(name);
        } catch (final IOException unreadable) {
            throw new IllegalStateException("could not read " + compose, unreadable);
        }
    }

    /** The keys under compose.yml's top-level {@code volumes:} block. */
    private static Set<String> composeVolumes() {
        final Path compose = findUpwards("compose.yml");
        try (Reader reader = Files.newBufferedReader(compose, StandardCharsets.UTF_8)) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> root = (Map<String, Object>) new Yaml().load(reader);
            @SuppressWarnings("unchecked")
            final Map<String, Object> volumes = (Map<String, Object>) root.get("volumes");
            assertNotNull(volumes, compose + " has no volumes block");
            return new LinkedHashSet<>(volumes.keySet());
        } catch (final IOException unreadable) {
            throw new IllegalStateException("could not read " + compose, unreadable);
        }
    }

    /** {@link StewardSpec} answering nothing but its own defaults. */
    private static StewardSpec defaults() {
        return new StewardSpec() {
            @Override
            public BunqSpec bunq() {
                // Empty credentials are a valid season: "no bank account". Nothing here asks bunq anything.
                return new BunqSpec() {};
            }

            @Override
            public ApiSpec api() {
                // Defaults: nothing here serves HTTP.
                return new ApiSpec() {};
            }

            @Override
            public DockerSpec docker() {
                // Defaults: this test is not about the daemon, and nothing here reads it.
                return new DockerSpec() {};
            }

            @Override
            public UpdateSpec update() {
                return new UpdateSpec() {};
            }

            @Override
            public BackupSpec backup() {
                return new BackupSpec() {
                    // backup.remote has no default of its own, so this hands its defaults back by name.
                    @Override
                    public RemoteSpec remote() {
                        return new RemoteSpec() {};
                    }

                    @Override
                    public RetentionSpec retention() {
                        return new RetentionSpec() {};
                    }
                };
            }

            @Override
            public DeployerSpec deployer() {
                // Defaults: this test never recreates a container.
                return new DeployerSpec() {};
            }
        };
    }

    @Test
    void theDatabaseCanWriteItsDumpWhereTheWorkerLaterLooksForIt() {
        // pg_dump runs inside postgres, so `backup.output-root` is a path in that container, not steward-worker's.
        final BackupSpec backup = defaults().backup();
        final String directory = backup.outputRoot();

        final String worker = writableMountAt("steward-worker", directory);
        final String database = writableMountAt(backup.databaseService(), directory);
        assertEquals(
                worker,
                database,
                backup.databaseService() + " writes the dump to "
                        + directory + " out of one volume and steward-worker reads " + directory
                        + " out of another, so the dump is saved where nothing ever looks for it.");
    }

    /**
     * The volume behind a service's mount at {@code path}, insisting it is not read-only.
     *
     * The destination is parsed from the right: a mount's default value can itself contain colons, so splitting on the
     * first one finds a piece of the default instead of the actual destination.
     */
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
    void composeYmlDoesNotPinBootstrapSoStewardYmlStillDecides() {
        // jcore's EnvOverlay skips a blank variable, so `${VAR:-}` leaves StewardSpec#bootstrap's own default in force.
        @SuppressWarnings("unchecked")
        final Map<String, Object> worker = (Map<String, Object>) services.get("steward-worker");
        @SuppressWarnings("unchecked")
        final Map<String, Object> environment = (Map<String, Object>) worker.get("environment");

        assertAll(
                () -> assertEquals(
                        "${STEWARD_WORKER_BOOTSTRAP:-}",
                        String.valueOf(environment.get("NORDTAL_STEWARD_BOOTSTRAP")),
                        "compose.yml carries a fallback for STEWARD_WORKER_BOOTSTRAP. A value here"
                                + " wins over steward.yml for ever, so the setting cannot be"
                                + " changed from the interface."),
                () -> assertTrue(
                        defaults().bootstrap(),
                        "StewardSpec#bootstrap is false. It is now the ONLY thing that makes a"
                                + " first deployment fill its empty volumes, so with it off nothing"
                                + " comes up without somebody running `steward-worker apply` on"
                                + " the host."));
    }

    @Test
    void everyImageOfOursDefaultsToOneTheReleaseWorkflowActuallyPushes() {
        // A deploy that only pulls never builds, so a tag nothing pushed fails like a private package.
        services.forEach((name, definition) -> {
            @SuppressWarnings("unchecked")
            final Map<String, Object> service = (Map<String, Object>) definition;
            final String image = String.valueOf(service.get("image"));
            if (!image.contains("nordtal/")) {
                return;
            }
            assertTrue(
                    image.contains(":-ghcr.io/nordtal/"),
                    "compose.yml's '" + name + "' defaults to the image " + image + ", which is not"
                            + " a ghcr.io/nordtal reference. A deploy pulls and never builds, so an"
                            + " image only this host can produce fails the deploy with `denied`.");
            // The tag is the literal `latest`: nothing pins an image, a bad release is fixed by a better one.
            assertTrue(
                    image.endsWith(":latest}"),
                    "compose.yml's '" + name + "' defaults to " + image + ", which is not `latest`."
                            + " Nothing pins an image any more; a bad release is fixed by publishing"
                            + " a better one.");
        });
    }

    @Test
    void theReleaseWorkflowPushesEveryImageComposeYmlExpectsToPull() throws IOException {
        // A correct default is not the same as release.yml pushing it; the gap looks like a private package `denied`.
        final String workflow = Files.readString(findUpwards(".github/workflows/release.yml"), StandardCharsets.UTF_8);
        services.forEach((name, definition) -> {
            @SuppressWarnings("unchecked")
            final Map<String, Object> service = (Map<String, Object>) definition;
            final String image = String.valueOf(service.get("image"));
            if (!image.contains(":-ghcr.io/nordtal/")) {
                return;
            }
            // The repository, not the service: one image serves all four Minecraft services.
            final String repository = image.substring(image.indexOf(":-ghcr.io/nordtal/") + 2, image.lastIndexOf(':'));
            assertTrue(
                    workflow.contains(repository + ":latest"),
                    "compose.yml's '" + name + "' pulls " + repository + ":latest, and"
                            + " .github/workflows/release.yml pushes no such tag. A deploy pulls and"
                            + " never builds, so that image exists only where somebody built it by"
                            + " hand and the deploy fails with `denied` everywhere else.");
        });
    }

    @Test
    void theDeployerDeploysTheProjectItWasStartedInNotOneOfItsOwn() throws IOException {
        // A disagreeing `--project-name` fails nothing visibly: a second stack comes up beside the running one.
        @SuppressWarnings("unchecked")
        final Map<String, Object> deployer = (Map<String, Object>) services.get("steward-deployer");
        @SuppressWarnings("unchecked")
        final Map<String, Object> environment = (Map<String, Object>) deployer.get("environment");
        final String declared = String.valueOf(environment.get("COMPOSE_PROJECT_NAME"));
        assertEquals(
                "${COMPOSE_PROJECT_NAME:-nordtal-s2}",
                declared,
                "compose.yml no longer hands steward-deployer the project name. Without it the"
                        + " service falls back to its own default, which is only the same value"
                        + " until somebody sets COMPOSE_PROJECT_NAME in .env.");

        final String source = Files.readString(
                findUpwards("steward-deployer/src/main/java/eu/nordtal/s2/steward/deployer/StewardDeployer.java"),
                StandardCharsets.UTF_8);
        assertTrue(
                source.contains("env(\"COMPOSE_PROJECT_NAME\", \"nordtal-s2\")"),
                "StewardDeployer's fallback project name is not `nordtal-s2` any more, and"
                        + " compose.yml's is. Two different defaults for the project name are two"
                        + " deployments of the same stack.");
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
                "smp requires DisplayTags but Topology does not list it, so steward-worker would"
                        + " never install it");
    }

    @Test
    void noMinecraftServiceExistsInComposeYmlThatTheTopologyDoesNotKnowAbout() {
        // Catches a backend added to compose.yml and not here, which the worker would then quietly never touch.
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
                                + " Add it to Topology.SERVICES - the worker will not touch it otherwise.");
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

    /** The host side of a compose mount - everything before the last colon-separated field pair. */
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
        final Path compose = findUpwards("compose.yml");
        try (Reader reader = Files.newBufferedReader(compose, StandardCharsets.UTF_8)) {
            final Object loaded = new Yaml().load(reader);
            @SuppressWarnings("unchecked")
            final Map<String, Object> root = (Map<String, Object>) loaded;
            @SuppressWarnings("unchecked")
            final Map<String, Object> services = (Map<String, Object>) root.get("services");
            assertNotNull(services, compose + " has no services block");
            return services;
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
