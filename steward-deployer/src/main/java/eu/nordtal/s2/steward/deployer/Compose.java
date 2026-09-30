package eu.nordtal.s2.steward.deployer;

import com.google.gson.JsonObject;
import eu.nordtal.s2.common.json.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every {@code docker compose} invocation this service makes, against the compose file baked into its image.
 *
 * Every {@code up} carries {@code --no-deps}, so recreating one service never drags in another.
 */
public final class Compose {

    private static final Logger log = LoggerFactory.getLogger(Compose.class);

    /**
     * This service's compose name, refused everywhere a service name is accepted.
     *
     * Recreating it would kill the process handling the request; the setup script renews it instead.
     */
    public static final String SELF = "steward-deployer";

    private final Path composeFile;
    private final Path envFile;
    private final Path projectDirectory;
    private final String projectName;
    private final LinkCounter linkCounter;

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
        this.composeFile = composeFile;
        this.envFile = envFile;
        this.projectDirectory = projectDirectory;
        this.projectName = projectName;
        this.linkCounter = linkCounter;
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
            return; // base() only adds --env-file when the path resolves.
        }
        final OptionalLong nlink = linkCounter.nlink(envFile);
        if (nlink.isPresent() && nlink.getAsLong() == 0) {
            throw new StaleEnvFileException(envFile
                    + " is a deleted inode still being served through this container's mount"
                    + " (link count 0). Something on the host replaced this file after the mount"
                    + " was set up, and every value read from it since is from before that change."
                    + " Refusing to deploy with it. Recreate steward-deployer against a directory"
                    + " mount, rather than a file mount, to fix it.");
        }
    }

    /** Thrown by {@link #assertEnvFileFresh}, as an {@link IOException} so every caller already propagates it. */
    public static final class StaleEnvFileException extends IOException {
        StaleEnvFileException(final String message) {
            super(message);
        }
    }

    /** The fixed head of every command line: which project, which file, which environment. */
    private List<String> base() {
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
        return command;
    }

    /**
     * Runs {@code up -d --no-deps} for named services, refusing steward-deployer.
     *
     * The list is never empty, since an empty one means every service and belongs to {@link #bootstrap}.
     */
    public int up(final List<String> services, final Consumer<String> output) throws IOException {
        assertEnvFileFresh();
        final List<String> command = base();
        command.addAll(List.of("up", "--detach", "--no-deps"));
        command.addAll(refuseSelf(services));
        return run(command, output);
    }

    /**
     * Runs {@code up -d --no-deps} with steward-deployer included, for {@code deployer up} only.
     *
     * That throwaway container is not the managed service, so creating steward-deployer from it is not a self-recreate.
     */
    public int bootstrap(final List<String> services, final Consumer<String> output) throws IOException {
        assertEnvFileFresh();
        final List<String> command = base();
        command.addAll(List.of("up", "--detach", "--no-deps"));
        command.addAll(services);
        return run(command, output);
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
        final List<String> command = base();
        command.addAll(List.of("up", "--detach", "--no-deps", "--force-recreate"));
        command.addAll(refuseSelf(List.of(service)));
        return command;
    }

    /** Returns whether this service's image is already on this host, without pulling. */
    public boolean hasLocalImage(final String service) {
        final Optional<String> image = imageOf(service);
        return image.isPresent() && imageExistsLocally(image.get());
    }

    /**
     * Pulls one service's image and answers whether the deployment can go on without it.
     *
     * A failed pull is tolerated only when the image is already here, which covers images pushed to no registry.
     */
    public PullOutcome pull(final String service, final Consumer<String> output) throws IOException {
        final List<String> command = base();
        command.addAll(List.of("pull", service));
        final int code = run(command, output);
        if (code == 0) {
            return PullOutcome.PULLED;
        }
        final Optional<String> image = imageOf(service);
        if (image.isPresent() && imageExistsLocally(image.get())) {
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

    private Map<String, String> config(final boolean allProfiles) throws IOException {
        final StringBuilder json = new StringBuilder();
        final int code =
                run(configCommand(allProfiles), line -> json.append(line).append('\n'));
        if (code != 0) {
            throw new IOException("docker compose config exited " + code);
        }
        final JsonObject root = Json.decode(json.toString(), JsonObject.class);
        final JsonObject services = root.getAsJsonObject("services");
        final Map<String, String> byName = new LinkedHashMap<>();
        for (final String name : services.keySet()) {
            final JsonObject service = services.getAsJsonObject(name);
            byName.put(name, service.has("image") ? service.get("image").getAsString() : "");
        }
        return byName;
    }

    /**
     * Returns the command line {@link #config} runs; {@code --profile "*"} is a top-level flag before the subcommand.
     */
    List<String> configCommand(final boolean allProfiles) {
        final List<String> command = base();
        if (allProfiles) {
            command.addAll(List.of("--profile", "*"));
        }
        command.addAll(List.of("config", "--format", "json"));
        return command;
    }

    /** Returns {@code compose ps} as JSON lines, which the interface draws the service list from. */
    public String state() throws IOException {
        final List<String> command = base();
        command.addAll(List.of("ps", "--all", "--format", "json"));
        final StringBuilder json = new StringBuilder();
        final int code = run(command, line -> json.append(line).append('\n'));
        if (code != 0) {
            throw new IOException("docker compose ps exited " + code);
        }
        return json.toString();
    }

    private Optional<String> imageOf(final String service) {
        try {
            // everyService: "not in this profile selection" is not "has no image".
            final String image = everyService().get(service);
            return image == null || image.isBlank() ? Optional.empty() : Optional.of(image);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private boolean imageExistsLocally(final String image) {
        try {
            return run(List.of("docker", "image", "inspect", image), line -> {}) == 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static List<String> refuseSelf(final List<String> services) {
        for (final String service : services) {
            if (SELF.equals(service)) {
                throw new IllegalArgumentException(
                        "steward-deployer will not recreate itself: the new container would replace "
                                + "the one running this request, and nobody would ever read the answer. "
                                + "The setup script on the host renews this service.");
            }
        }
        return services;
    }

    /** Runs one command and hands every line to {@code output} as it arrives, stderr included. */
    private int run(final List<String> command, final Consumer<String> output) throws IOException {
        log.info("$ {}", String.join(" ", command));
        final Process process =
                new ProcessBuilder(command).redirectErrorStream(true).start();
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
