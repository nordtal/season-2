package eu.nordtal.season.stewardagent;

import com.google.gson.JsonElement;
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
 * Every {@code docker compose} command line of this service and of {@code dev}.
 *
 * Every {@code up} carries {@code --no-deps}.
 */
public final class Compose {

    private static final Logger log = LoggerFactory.getLogger(Compose.class);

    private static final Pattern ENV_FILE_NAME = Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=");

    /** The compose name of steward-agent. Every method that takes a service name refuses it. */
    public static final String SELF = AgentWire.SERVICE;

    /** The path of compose.yml in steward-agent's image. */
    public static final String IN_IMAGE = "/app/compose.yml";

    public static final String MIGRATE = Topology.MIGRATE;

    private final Path composeFile;
    private final Path envFile;
    private final Path projectDirectory;
    private final String projectName;
    private final LinkCounter linkCounter;

    /** Overrides the process environment on every command, so the release outranks the env file. */
    private final Map<String, String> environment;

    public Compose(final Path composeFile, final Path envFile, final Path projectDirectory, final String projectName) {
        this(composeFile, envFile, projectDirectory, projectName, Compose::posixLinkCount);
    }

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

    public String projectName() {
        return projectName;
    }

    /**
     * Returns a Compose for the same project, env file and directory at another release, from {@code composeFile}.
     */
    public Compose atRelease(final String release) {
        return atRelease(release, composeFile);
    }

    public Compose atRelease(final String release, final Path composeFile) {
        return new Compose(
                composeFile, envFile, projectDirectory, projectName, linkCounter, Map.of("NORDTAL_RELEASE", release));
    }

    public String oneShotName() {
        return projectName + "-" + SELF + "-run";
    }

    /**
     * Starts a one-shot steward-agent detached on the run {@code id}.
     *
     * @return compose's exit status
     */
    public int runOneShot(final long id, final Consumer<String> output) throws IOException {
        assertEnvFileFresh();
        return run(oneShotCommand(id), output);
    }

    List<String> oneShotCommand(final long id) {
        return command(List.of(
                "run", "--detach", "--rm", "--no-deps", "--name", oneShotName(), SELF, "run", Long.toString(id)));
    }

    @FunctionalInterface
    interface LinkCounter {
        OptionalLong nlink(Path path);
    }

    private static OptionalLong posixLinkCount(final Path path) {
        try {
            final Object nlink = Files.getAttribute(path, "unix:nlink");
            return nlink instanceof Number n ? OptionalLong.of(n.longValue()) : OptionalLong.empty();
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Throws if {@link #envFile} is a deleted inode behind a stale bind mount.
     *
     * A host rotation replaces the file. A link count of zero shows the old inode.
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

    public static final class StaleEnvFileException extends IOException {
        StaleEnvFileException(final String message) {
            super(message);
        }
    }

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
     * The list is never empty: an empty list means every service and belongs to {@link #bootstrap}.
     */
    public int up(final List<String> services, final Consumer<String> output) throws IOException {
        assertEnvFileFresh();
        handOver(refuseSelf(services), output);
        final List<String> command = command(List.of("up", "--detach", "--no-deps"));
        command.addAll(refuseSelf(services));
        return run(command, output);
    }

    /**
     * Runs {@code up -d --no-deps} including steward-agent, for {@code agent up} only.
     *
     * The throwaway container that runs it is not the managed service.
     */
    public int bootstrap(final List<String> services, final Consumer<String> output) throws IOException {
        assertEnvFileFresh();
        handOver(services, output);
        final List<String> command = command(List.of("up", "--detach", "--no-deps"));
        command.addAll(services);
        return run(command, output);
    }

    /**
     * Runs the migrate service through {@code up} and waits for it.
     *
     * @return its exit status, 0 only on a current schema
     */
    public int migrate(final Consumer<String> output) throws IOException {
        assertEnvFileFresh();
        return run(migrateCommand(), output);
    }

    List<String> migrateCommand() {
        return command(List.of("up", "--no-deps", "--abort-on-container-exit", "--exit-code-from", MIGRATE, MIGRATE));
    }

    /**
     * Runs {@code up -d --no-deps --force-recreate} without pulling.
     *
     * The service needs a local image, see {@link #hasLocalImage}.
     */
    public int recreate(final String service, final Consumer<String> output) throws IOException {
        assertEnvFileFresh();
        handOver(List.of(refuseSelf(service)), output);
        return run(recreateCommand(service), output);
    }

    /**
     * The user and writable mounts of a service.
     *
     * @param uid the numeric uid, never 0
     * @param gid the numeric gid, the uid's when {@code user:} names none
     * @param targets the writable mounts inside the container
     */
    record Owner(String uid, String gid, List<String> targets) {

        Owner {
            targets = List.copyOf(targets);
        }
    }

    /**
     * Reads a service's owner from its definition as {@code config} resolves it.
     *
     * The result is empty for a service that runs as root, names its user by name or mounts nothing writable.
     */
    static Optional<Owner> ownerOf(final JsonObject service) {
        if (!service.has("user")) {
            return Optional.empty();
        }
        final String[] user = service.get("user").getAsString().strip().split(":", 2);
        final String uid = user[0];
        final String gid = user.length > 1 && !user[1].isBlank() ? user[1] : uid;
        if (!uid.matches("\\d+") || !gid.matches("\\d+") || Long.parseLong(uid) == 0) {
            return Optional.empty();
        }
        final List<String> targets = new ArrayList<>();
        if (service.has("volumes")) {
            for (final JsonElement element : service.getAsJsonArray("volumes")) {
                final JsonObject mount = element.getAsJsonObject();
                final String type = mount.has("type") ? mount.get("type").getAsString() : "";
                final boolean readOnly =
                        mount.has("read_only") && mount.get("read_only").getAsBoolean();
                if (("bind".equals(type) || "volume".equals(type)) && !readOnly && mount.has("target")) {
                    targets.add(mount.get("target").getAsString());
                }
            }
        }
        return targets.isEmpty() ? Optional.empty() : Optional.of(new Owner(uid, gid, targets));
    }

    /**
     * Gives each service's user ownership of everything the service mounts, before its container starts.
     *
     * It runs in a temporary container of the service's image. A failure is logged and the start goes on.
     */
    public void handOver(final List<String> services, final Consumer<String> output) throws IOException {
        final JsonObject definitions = definitions(true);
        for (final String service : services) {
            final Optional<Owner> owner =
                    definitions.has(service) ? ownerOf(definitions.getAsJsonObject(service)) : Optional.empty();
            if (owner.isEmpty()) {
                continue;
            }
            final int code = run(handOverCommand(service, owner.get()), output);
            if (code != 0) {
                output.accept("handing " + service + "'s volumes to "
                        + owner.get().uid() + ":" + owner.get().gid() + " exited " + code + "; starting it anyway");
                log.warn("handing {}'s volumes to its user exited {}", service, code);
            }
        }
    }

    List<String> handOverCommand(final String service, final Owner owner) {
        final String uid = owner.uid();
        final String gid = owner.gid();
        final List<String> arguments = new ArrayList<>(List.of(
                "run",
                "--rm",
                "--no-deps",
                "-T",
                "--pull",
                "never",
                "--user",
                "0:0",
                "--cap-add",
                "CHOWN",
                // Root without it cannot list a directory the user made private, such as bunq's context.
                "--cap-add",
                "DAC_READ_SEARCH",
                "--entrypoint",
                "find",
                service));
        arguments.addAll(owner.targets());
        arguments.addAll(List.of(
                "(",
                "!",
                "-user",
                uid,
                "-o",
                "!",
                "-group",
                gid,
                ")",
                "-exec",
                "chown",
                "-h",
                uid + ":" + gid,
                "{}",
                "+"));
        return command(arguments);
    }

    List<String> recreateCommand(final String service) {
        return command(List.of("up", "--detach", "--no-deps", "--force-recreate", refuseSelf(service)));
    }

    /**
     * Returns whether the image of the service is on this host.
     *
     * @param isHere asks the daemon about one image reference
     */
    public boolean hasLocalImage(final String service, final Predicate<String> isHere) {
        final Optional<String> image = imageOf(service);
        return image.isPresent() && isHere.test(image.get());
    }

    /**
     * Pulls the image of one service. A failed pull passes when the image is here.
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
        PULLED,
        LOCAL_IMAGE_KEPT,
        FAILED
    }

    /**
     * Returns every service of the active profiles in file order, with its image.
     *
     * Standbys sit in a profile that no ordinary selection activates.
     */
    public Map<String, String> services() throws IOException {
        return config(false);
    }

    /**
     * Returns every service of the compose file in any profile.
     *
     * {@code config} answers only for active profiles, so this activates all of them.
     */
    public Map<String, String> everyService() throws IOException {
        return config(true);
    }

    /**
     * Returns the definition of every service in any profile as {@code config} resolves it.
     *
     * Steward reads the labels in it through {@code /api/topology}.
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
     * Returns compose's hash of each service definition. Compose also labels each container with it.
     *
     * The hash is taken after interpolation, so a new release tag or env file changes it.
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

    List<String> configCommand(final boolean allProfiles) {
        final List<String> config = List.of("config", "--format", "json");
        return command(allProfiles ? concat(List.of("--profile", "*"), config) : config);
    }

    public Optional<String> imageOf(final String service) {
        try {
            // Unlike {@code services()}, it finds a service in any profile.
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

    static String refuseSelf(final String service) {
        if (SELF.equals(service)) {
            throw new IllegalArgumentException("steward-agent will not recreate itself: the new container would replace"
                    + " the one running this request, and nobody would ever read the answer. The one-shot a"
                    + " run is handed to renews it last.");
        }
        return service;
    }

    /**
     * Returns the inherited environment without the names the env file sets, plus the release.
     *
     * Compose ranks a process environment above the file.
     */
    Map<String, String> commandEnvironment(final Map<String, String> inherited) throws IOException {
        final Map<String, String> result = new HashMap<>(inherited);
        result.keySet().removeAll(envFileNames());
        result.putAll(environment);
        return result;
    }

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
