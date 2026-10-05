package eu.nordtal.season.stewardagent;

import com.google.gson.JsonObject;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.Topology;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every {@code docker compose} command line there is, this service's and the ones {@code dev} runs on its terminal.
 *
 * Every {@code up} carries {@code --no-deps}, so recreating one service never drags in another.
 */
public final class Compose {

    private static final Logger log = LoggerFactory.getLogger(Compose.class);

    /** A name an env file sets, as compose reads one: optionally exported, then the name and an equals sign. */
    private static final Pattern ENV_FILE_NAME = Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=");

    /**
     * This service's compose name, refused everywhere a service name is accepted.
     *
     * Recreating it would kill the process handling the request; the one-shot a run is handed to renews it.
     */
    public static final String SELF = AgentWire.SERVICE;

    /** Where steward-agent's image carries this file; a newer release's is copied out of its image from here. */
    public static final String IN_IMAGE = "/app/compose.yml";

    /** The service that applies the schema from this image and exits, which every database login waits for. */
    public static final String MIGRATE = Topology.MIGRATE;

    private final Path composeFile;
    private final Path envFile;
    private final Path projectDirectory;
    private final String projectName;
    private final LinkCounter linkCounter;

    /** Set on every command over this process's own, so the release a command is for outranks the env file. */
    private final Map<String, String> environment;

    public Compose(final Path composeFile, final Path envFile, final Path projectDirectory, final String projectName) {
        this(composeFile, envFile, projectDirectory, projectName, Compose::posixLinkCount);
    }

    /** Creates a Compose with a fake link counter, for tests without a real bind mount. */
    Compose(
            final Path composeFile,
            final Path envFile,
            final Path projectDirectory,
            final String projectName,
            final LinkCounter linkCounter) {
        this(composeFile, envFile, projectDirectory, projectName, linkCounter, Map.of());
    }

    private Compose(
            final Path composeFile,
            final Path envFile,
            final Path projectDirectory,
            final String projectName,
            final LinkCounter linkCounter,
            final Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
        this.composeFile = composeFile;
        this.envFile = envFile;
        this.projectDirectory = projectDirectory;
        this.projectName = projectName;
        this.linkCounter = linkCounter;
    }

    /** The compose project every command names, which is also how the daemon labels its containers. */
    public String projectName() {
        return projectName;
    }

    /**
     * The same project, environment file and directory at another release, read from {@code composeFile}.
     *
     * Every image of ours is tagged {@code NORDTAL_RELEASE}, so this is how a newer release's images are named.
     */
    public Compose atRelease(final String release) {
        return atRelease(release, composeFile);
    }

    /** {@link #atRelease(String)}, read from that release's own compose file. */
    public Compose atRelease(final String release, final Path composeFile) {
        return new Compose(
                composeFile, envFile, projectDirectory, projectName, linkCounter, Map.of("NORDTAL_RELEASE", release));
    }

    /** The name of the one-shot steward-agent a run is handed to; only one runs at a time. */
    public String oneShotName() {
        return projectName + "-" + SELF + "-run";
    }

    /**
     * Starts a one-shot steward-agent on the claimed run {@code id}, detached, removed once it exits.
     *
     * @return compose's exit status, 0 once the container runs
     */
    public int runOneShot(final long id, final Consumer<String> output) throws IOException {
        assertEnvFileFresh();
        return run(oneShotCommand(id), output);
    }

    /** Returns the command line {@link #runOneShot} runs. */
    List<String> oneShotCommand(final long id) {
        return command(List.of(
                "run", "--detach", "--rm", "--no-deps", "--name", oneShotName(), SELF, "run", Long.toString(id)));
    }

    /** Counts the hard links of the file at a path, empty when that cannot be determined. */
    @FunctionalInterface
    interface LinkCounter {
        OptionalLong nlink(Path path);
    }

    /** Reads {@code st_nlink} through the "unix" attribute view, empty rather than a refusal when unavailable. */
    private static OptionalLong posixLinkCount(final Path path) {
        try {
            final Object nlink = Files.getAttribute(path, "unix:nlink");
            return nlink instanceof Number n ? OptionalLong.of(n.longValue()) : OptionalLong.empty();
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Refuses to go on if {@link #envFile} is a deleted inode behind a stale file bind mount.
     *
     * A host rotation replaces the file, and only the orphaned inode's link count of zero shows it from in here.
     */
    void assertEnvFileFresh() throws IOException {
        if (!Files.exists(envFile)) {
            return; // command() only adds --env-file when the path resolves.
        }
        final OptionalLong nlink = linkCounter.nlink(envFile);
        if (nlink.isPresent() && nlink.getAsLong() == 0) {
            throw new StaleEnvFileException(envFile
                    + " is a deleted inode still being served through this container's mount"
                    + " (link count 0). Something on the host replaced this file after the mount"
                    + " was set up, and every value read from it since is from before that change."
                    + " Refusing to deploy with it. Recreate steward-agent against a directory"
                    + " mount, rather than a file mount, to fix it.");
        }
    }

    /** Thrown by {@link #assertEnvFileFresh}, as an {@link IOException} so every caller already propagates it. */
    public static final class StaleEnvFileException extends IOException {
        StaleEnvFileException(final String message) {
            super(message);
        }
    }

    /**
     * Returns the command line for {@code arguments}, for a caller that runs it with its own input and output.
     *
     * Every line starts the same way: which project, which file, which environment.
     */
    public List<String> command(final List<String> arguments) {
        final List<String> command = new ArrayList<>(List.of(
                "docker",
                "compose",
                "--project-name",
                projectName,
                "--project-directory",
                projectDirectory.toString(),
                "--file",
                composeFile.toString()));
        if (Files.exists(envFile)) {
            command.add("--env-file");
            command.add(envFile.toString());
        }
        command.addAll(arguments);
        return command;
    }

    /**
     * Runs {@code up -d --no-deps} for named services, refusing steward-agent.
     *
     * The list is never empty, since an empty one means every service and belongs to {@link #bootstrap}.
     */
    public int up(final List<String> services, final Consumer<String> output) throws IOException {
        assertEnvFileFresh();
        final List<String> command = command(List.of("up", "--detach", "--no-deps"));
        command.addAll(refuseSelf(services));
        return run(command, output);
    }

    /**
     * Runs {@code up -d --no-deps} with steward-agent included, for {@code agent up} only.
     *
     * That throwaway container is not the managed service, so creating steward-agent from it is not a self-recreate.
     */
    public int bootstrap(final List<String> services, final Consumer<String> output) throws IOException {
        assertEnvFileFresh();
        final List<String> command = command(List.of("up", "--detach", "--no-deps"));
        command.addAll(services);
        return run(command, output);
    }

    /**
     * Runs the migrate service and waits for it, in an update or after a restore replaced the database.
     * Through {@code up}, so its container is this release's, which every service's {@code depends_on} reads.
     *
     * @return its exit status, 0 only on a current schema
     */
    public int migrate(final Consumer<String> output) throws IOException {
        assertEnvFileFresh();
        return run(migrateCommand(), output);
    }

    /** Returns the command line {@link #migrate} runs. */
    List<String> migrateCommand() {
        return command(List.of("up", "--no-deps", "--abort-on-container-exit", "--exit-code-from", MIGRATE, MIGRATE));
    }

    /**
     * Runs {@code up -d --no-deps --force-recreate} from the image already on this host, never pulling.
     *
     * A service without a local image cannot be recreated; callers check {@link #hasLocalImage} first.
     */
    public int recreate(final String service, final Consumer<String> output) throws IOException {
        assertEnvFileFresh();
        return run(recreateCommand(service), output);
    }

    /** Returns the command line {@link #recreate} runs, so a test can check that no token pulls. */
    List<String> recreateCommand(final String service) {
        return command(List.of("up", "--detach", "--no-deps", "--force-recreate", refuseSelf(service)));
    }

    /**
     * Returns whether this service's image is already on this host, without pulling.
     *
     * @param isHere asks the daemon about one image reference
     */
    public boolean hasLocalImage(final String service, final Predicate<String> isHere) {
        final Optional<String> image = imageOf(service);
        return image.isPresent() && isHere.test(image.get());
    }

    /**
     * Pulls one service's image; a failed pull is tolerated when the image is here, as one pushed nowhere is.
     *
     * @param isHere asks the daemon about one image reference
     */
    public PullOutcome pull(final String service, final Consumer<String> output, final Predicate<String> isHere)
            throws IOException {
        final int code = run(command(List.of("pull", service)), output);
        if (code == 0) {
            return PullOutcome.PULLED;
        }
        final Optional<String> image = imageOf(service);
        if (image.isPresent() && isHere.test(image.get())) {
            output.accept("pull failed for " + service + ", but " + image.get()
                    + " is on this host - continuing with the local image");
            log.warn("pull failed for {}; using the local image {}", service, image.get());
            return PullOutcome.LOCAL_IMAGE_KEPT;
        }
        return PullOutcome.FAILED;
    }

    public enum PullOutcome {
        /** The registry answered and the image is current. */
        PULLED,
        /** The registry did not answer for this one, but the image is here. See {@link #pull}. */
        LOCAL_IMAGE_KEPT,
        /** No image, from anywhere. The deployment stops. */
        FAILED
    }

    /**
     * Returns every service the active profile selection carries, in file order, with its image.
     *
     * Standbys sit in a profile no ordinary selection carries, so a whole-stack deploy never starts them.
     */
    public Map<String, String> services() throws IOException {
        return config(false);
    }

    /**
     * Returns every service the compose file defines, whatever profile it sits in.
     *
     * {@code config} answers only for enabled profiles, so this enables all of them for one read.
     */
    public Map<String, String> everyService() throws IOException {
        return config(true);
    }

    /**
     * Returns every service the compose file defines, in any profile, as {@code config} resolves it.
     *
     * The labels in it are the topology; steward reads them through {@code /api/topology}.
     */
    public JsonObject definitions() throws IOException {
        return definitions(true);
    }

    private Map<String, String> config(final boolean allProfiles) throws IOException {
        final JsonObject services = definitions(allProfiles);
        final Map<String, String> byName = new LinkedHashMap<>();
        for (final String name : services.keySet()) {
            final JsonObject service = services.getAsJsonObject(name);
            byName.put(name, service.has("image") ? service.get("image").getAsString() : "");
        }
        return byName;
    }

    private JsonObject definitions(final boolean allProfiles) throws IOException {
        final StringBuilder json = new StringBuilder();
        final int code =
                run(configCommand(allProfiles), line -> json.append(line).append('\n'));
        if (code != 0) {
            throw new IOException("docker compose config exited " + code);
        }
        return Json.decode(json.toString(), JsonObject.class).getAsJsonObject("services");
    }

    /**
     * Returns compose's hash of each service's definition, which it also labels every container it makes with.
     *
     * Compose hashes after interpolation, so a new release's tag and a changed environment file both change it.
     */
    public Map<String, String> hashes() throws IOException {
        final Map<String, String> hashes = new LinkedHashMap<>();
        final List<String> unreadable = new ArrayList<>();
        final int code = run(command(List.of("config", "--hash", "*")), line -> {
            final String trimmed = line.trim();
            final int space = trimmed.indexOf(' ');
            if (space > 0 && trimmed.indexOf(' ', space + 1) < 0) {
                hashes.put(trimmed.substring(0, space), trimmed.substring(space + 1));
            } else if (!trimmed.isEmpty()) {
                unreadable.add(line);
            }
        });
        if (code != 0) {
            throw new IOException("docker compose config --hash exited " + code + ": " + String.join(" ", unreadable));
        }
        return hashes;
    }

    /**
     * Returns the command line {@link #config} runs; {@code --profile "*"} is a top-level flag before the subcommand.
     */
    List<String> configCommand(final boolean allProfiles) {
        final List<String> config = List.of("config", "--format", "json");
        return command(allProfiles ? concat(List.of("--profile", "*"), config) : config);
    }

    /** The image a service of this file names, after interpolation, in whatever profile it sits. */
    public Optional<String> imageOf(final String service) {
        try {
            // everyService: "not in this profile selection" is not "has no image".
            final String image = everyService().get(service);
            return image == null || image.isBlank() ? Optional.empty() : Optional.of(image);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static List<String> concat(final List<String> first, final List<String> second) {
        final List<String> both = new ArrayList<>(first);
        both.addAll(second);
        return both;
    }

    private static List<String> refuseSelf(final List<String> services) {
        services.forEach(Compose::refuseSelf);
        return services;
    }

    /** Throws when {@code service} is steward-agent itself, which only the setup script renews. */
    static String refuseSelf(final String service) {
        if (SELF.equals(service)) {
            throw new IllegalArgumentException("steward-agent will not recreate itself: the new container would replace"
                    + " the one running this request, and nobody would ever read the answer. The one-shot a"
                    + " run is handed to renews it last.");
        }
        return service;
    }

    /**
     * Returns a command's environment: the inherited one less every name the env file sets, then the release.
     *
     * Compose ranks a process's environment above the file, and this container's was copied from the file when made.
     */
    Map<String, String> commandEnvironment(final Map<String, String> inherited) throws IOException {
        final Map<String, String> result = new HashMap<>(inherited);
        result.keySet().removeAll(envFileNames());
        result.putAll(environment);
        return result;
    }

    /** Returns every name the env file sets, none when there is no file. */
    private Set<String> envFileNames() throws IOException {
        final Set<String> names = new LinkedHashSet<>();
        if (!Files.isRegularFile(envFile)) {
            return names;
        }
        for (final String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
            final Matcher name = ENV_FILE_NAME.matcher(line);
            if (name.find()) {
                names.add(name.group(1));
            }
        }
        return names;
    }

    /** Runs one command and hands every line to {@code output} as it arrives, stderr included. */
    private int run(final List<String> command, final Consumer<String> output) throws IOException {
        log.info("$ {}", String.join(" ", command));
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        final Map<String, String> inherited = new HashMap<>(builder.environment());
        builder.environment().clear();
        builder.environment().putAll(commandEnvironment(inherited));
        final Process process = builder.start();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.accept(line);
            }
        }
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IOException("interrupted while waiting for: " + String.join(" ", command), e);
        }
    }
}
