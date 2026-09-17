package eu.nordtal.s2.steward.deployer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.function.Consumer;

/**
 * Every {@code docker compose} invocation this service makes, in one place.
 *
 * <p><b>The compose file is baked into the image</b> (§8b). Nothing reads a working tree on the
 * host, nothing syncs a directory, and "which compose file is live" is answered by the image tag
 * of this container rather than by a commit hash somebody has to look up.</p>
 *
 * <p><b>Dependencies are never pulled along.</b> Every {@code up} carries {@code --no-deps}. Arcane
 * called compose with {@code RecreateDependencies = RecreateDiverged} and nobody could change it,
 * which is why a recreate of one backend could recreate the service every backend waits for.
 * Here it is one flag.</p>
 */
public final class Compose {

    private static final Logger log = LoggerFactory.getLogger(Compose.class);
    private static final Gson GSON = new Gson();

    /**
     * This service, by its compose name. It is refused everywhere a service name is accepted: the
     * recreate would take down the container the request is running in, and the caller would never
     * learn what happened. The setup script on the host renews this one (§9c).
     */
    public static final String SELF = "steward-deployer";

    private final Path composeFile;
    private final Path envFile;
    private final Path projectDirectory;
    private final String projectName;

    public Compose(Path composeFile, Path envFile, Path projectDirectory, String projectName) {
        this.composeFile = composeFile;
        this.envFile = envFile;
        this.projectDirectory = projectDirectory;
        this.projectName = projectName;
    }

    /** The fixed head of every command line: which project, which file, which environment. */
    private List<String> base() {
        List<String> command = new ArrayList<>(List.of(
                "docker", "compose",
                "--project-name", projectName,
                "--project-directory", projectDirectory.toString(),
                "--file", composeFile.toString()));
        if (Files.exists(envFile)) {
            command.add("--env-file");
            command.add(envFile.toString());
        }
        return command;
    }

    /**
     * {@code up -d --no-deps <services>}, for named services, steward-deployer refused.
     *
     * <p>The list is never empty here: an empty one means <i>all</i> services to compose, which is
     * the bootstrap and belongs to {@link #bootstrap}. {@code --no-deps} keeps compose from
     * dragging in a dependency nobody asked about - harmless when everything is named, load-bearing
     * when one service is.</p>
     */
    public int up(List<String> services, Consumer<String> output) throws IOException {
        List<String> command = base();
        command.addAll(List.of("up", "--detach", "--no-deps"));
        command.addAll(refuseSelf(services));
        return run(command, output);
    }

    /**
     * {@code up -d --no-deps <services>}, <b>steward-deployer included</b>.
     *
     * <p><b>Only {@code deployer up} may call this</b> - the throwaway container the setup script
     * runs with {@code docker run --rm}. That process is not the compose-managed service, so
     * creating steward-deployer from it is not a container recreating itself; it is how §9c renews
     * this service, and without it the bootstrap would bring up a stack with no deployer in it.</p>
     *
     * <p>Everything else goes through {@link #up}, which refuses. The two used to be one method
     * that passed an empty list on the whole-stack path - and an empty list means <i>every</i>
     * service to compose, so the refusal was skipped exactly when it mattered most.</p>
     */
    public int bootstrap(List<String> services, Consumer<String> output) throws IOException {
        List<String> command = base();
        command.addAll(List.of("up", "--detach", "--no-deps"));
        command.addAll(services);
        return run(command, output);
    }

    /** {@code up -d --no-deps --force-recreate <service>}: a new container from the current image. */
    public int recreate(String service, Consumer<String> output) throws IOException {
        List<String> command = base();
        command.addAll(List.of("up", "--detach", "--no-deps", "--force-recreate"));
        command.addAll(refuseSelf(List.of(service)));
        return run(command, output);
    }

    /**
     * Pulls one service's image, and answers whether the deployment can go on without it.
     *
     * <p><b>A failed pull is not automatically fatal, and this is the one place that is true.</b>
     * During the alpha {@code steward-ui} is built on the host and pushed to no registry, so a pull
     * of it answers {@code denied} exactly like an image that does not exist. Tolerating that
     * blindly would hide a real registry outage, so the failure is only tolerated when the image is
     * already on this host - which is the difference between "we have it" and "we cannot get it".
     *
     * <p>This tolerance is temporary. It falls away the moment steward-ui has a release, and the
     * check that replaces it is the ordinary one: a pull that fails, fails.</p>
     */
    public PullOutcome pull(String service, Consumer<String> output) throws IOException {
        List<String> command = base();
        command.addAll(List.of("pull", service));
        int code = run(command, output);
        if (code == 0) {
            return PullOutcome.PULLED;
        }
        Optional<String> image = imageOf(service);
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

    /** Every service the compose file defines, in file order, with the image each one runs. */
    public Map<String, String> services() throws IOException {
        List<String> command = base();
        command.addAll(List.of("config", "--format", "json"));
        StringBuilder json = new StringBuilder();
        int code = run(command, line -> json.append(line).append('\n'));
        if (code != 0) {
            throw new IOException("docker compose config exited " + code);
        }
        JsonObject root = GSON.fromJson(json.toString(), JsonObject.class);
        JsonObject services = root.getAsJsonObject("services");
        Map<String, String> byName = new LinkedHashMap<>();
        for (String name : services.keySet()) {
            JsonObject service = services.getAsJsonObject(name);
            byName.put(name, service.has("image") ? service.get("image").getAsString() : "");
        }
        return byName;
    }

    /** {@code compose ps} as JSON lines, which is what the interface draws the service list from. */
    public String state() throws IOException {
        List<String> command = base();
        command.addAll(List.of("ps", "--all", "--format", "json"));
        StringBuilder json = new StringBuilder();
        int code = run(command, line -> json.append(line).append('\n'));
        if (code != 0) {
            throw new IOException("docker compose ps exited " + code);
        }
        return json.toString();
    }

    private Optional<String> imageOf(String service) {
        try {
            String image = services().get(service);
            return image == null || image.isBlank() ? Optional.empty() : Optional.of(image);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private boolean imageExistsLocally(String image) {
        try {
            return run(List.of("docker", "image", "inspect", image), line -> { }) == 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static List<String> refuseSelf(List<String> services) {
        for (String service : services) {
            if (SELF.equals(service)) {
                throw new IllegalArgumentException(
                        "steward-deployer will not recreate itself: the new container would replace "
                        + "the one running this request, and nobody would ever read the answer. "
                        + "The setup script on the host renews this service.");
            }
        }
        return services;
    }

    /**
     * Runs one command and hands every line to {@code output} as it arrives.
     *
     * <p>stderr is merged into stdout on purpose: compose writes its progress there, and a
     * deployment report that drops the progress and keeps only the summary is the report nobody can
     * use when something goes wrong.</p>
     */
    private int run(List<String> command, Consumer<String> output) throws IOException {
        log.info("$ {}", String.join(" ", command));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
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
