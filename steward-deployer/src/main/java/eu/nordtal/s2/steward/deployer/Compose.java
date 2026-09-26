package eu.nordtal.s2.steward.deployer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
    private final LinkCounter linkCounter;

    public Compose(Path composeFile, Path envFile, Path projectDirectory, String projectName) {
        this(composeFile, envFile, projectDirectory, projectName, Compose::posixLinkCount);
    }

    /**
     * The four-argument constructor with the inode check swapped out - for tests only.
     *
     * <p>A real orphaned inode (steward/102's whole bug) only exists behind an actual bind mount: a
     * plain file, deleted on the same filesystem a test runs on, simply stops resolving by path at
     * all, which is a different and much less interesting failure than the one this class defends
     * against. Faking the link count is what lets {@link #assertEnvFileFresh} be tested without a
     * Docker daemon, the same way {@link ComposeRefusesItselfTest} needs none.</p>
     */
    Compose(Path composeFile, Path envFile, Path projectDirectory, String projectName, LinkCounter linkCounter) {
        this.composeFile = composeFile;
        this.envFile = envFile;
        this.projectDirectory = projectDirectory;
        this.projectName = projectName;
        this.linkCounter = linkCounter;
    }

    /** How many hard links the file at this path has - {@link Optional#empty()} if that cannot be
     * determined, which is not itself a reason to refuse a deployment (see {@link #posixLinkCount}). */
    @FunctionalInterface
    interface LinkCounter {
        OptionalLong nlink(Path path);
    }

    /**
     * {@code st_nlink}, read through java.nio's "unix" attribute view.
     *
     * <p>Anything this cannot answer - a filesystem with no such view, most likely - comes back
     * empty rather than as a refusal: the check this backs is an added safety net over the ordinary
     * deploy path, and a filesystem quirk that made it unreadable must not make deployments stop
     * working altogether, which would be a worse regression than the one it guards against.</p>
     */
    private static OptionalLong posixLinkCount(Path path) {
        try {
            Object nlink = Files.getAttribute(path, "unix:nlink");
            return nlink instanceof Number n ? OptionalLong.of(n.longValue()) : OptionalLong.empty();
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Refuses to go on if {@link #envFile} is a deleted inode still being served through a stale
     * mount (steward/102).
     *
     * <p>A FILE bind mount follows the inode, not the path: once the host replaces this file - a
     * temp file and a {@code mv}, which is how a secret is rotated rather than edited - the mount
     * here keeps serving the orphaned copy for the rest of this container's life. Nothing about
     * that looks wrong from in here: {@code Files.exists} stays true, the path resolves, and every
     * value is just from before the rotation. The one thing that <b>does</b> differ is the orphaned
     * inode's own link count: a bind mount is not a hard link, so when the host's directory entry
     * for it disappears, the count the kernel reports drops to zero - visible through the mount,
     * because {@code stat} answers with the inode's real, system-wide link count regardless of which
     * mount you asked it through. A directory mount (the fix for steward/102) never produces this:
     * every lookup under it walks the host directory fresh, so whatever it finds always has a real
     * link. Called at start-up and again before every {@code up} - {@code serve} can run for days,
     * and the file can be rotated at any point in that time, not only once at boot.</p>
     */
    void assertEnvFileFresh() throws IOException {
        if (!Files.exists(envFile)) {
            return; // base() itself only adds --env-file when the path resolves; nothing to check.
        }
        OptionalLong nlink = linkCounter.nlink(envFile);
        if (nlink.isPresent() && nlink.getAsLong() == 0) {
            throw new StaleEnvFileException(envFile
                    + " is a deleted inode still being served through this container's mount"
                    + " (link count 0). Something on the host replaced this file after the mount"
                    + " was set up, and every value read from it since is from before that change."
                    + " Refusing to deploy with it. See steward/102 - recreating steward-deployer"
                    + " against a directory mount, rather than a file mount, is the fix.");
        }
    }

    /** Thrown by {@link #assertEnvFileFresh}. A plain {@link IOException} so every existing caller
     * of {@link #up}, {@link #bootstrap} and {@link #recreate} already propagates it correctly. */
    public static final class StaleEnvFileException extends IOException {
        StaleEnvFileException(String message) {
            super(message);
        }
    }

    /** The fixed head of every command line: which project, which file, which environment. */
    private List<String> base() {
        List<String> command = new ArrayList<>(List.of(
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
     * {@code up -d --no-deps <services>}, for named services, steward-deployer refused.
     *
     * <p>The list is never empty here: an empty one means <i>all</i> services to compose, which is
     * the bootstrap and belongs to {@link #bootstrap}. {@code --no-deps} keeps compose from
     * dragging in a dependency nobody asked about - harmless when everything is named, load-bearing
     * when one service is.</p>
     */
    public int up(List<String> services, Consumer<String> output) throws IOException {
        assertEnvFileFresh();
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
        assertEnvFileFresh();
        List<String> command = base();
        command.addAll(List.of("up", "--detach", "--no-deps"));
        command.addAll(services);
        return run(command, output);
    }

    /**
     * {@code up -d --no-deps --force-recreate <service>}: a new container from the image that is
     * already on this host.
     *
     * <p><b>Nothing is pulled here, and that is season-2-ops/134.</b> Until 2026-09-19 the recreate
     * route pulled first, which meant the one button an admin reaches for to un-wedge a container
     * silently replaced a locally built image with the published one - measured on {@code
     * steward-ui}: {@code md5sum /app/app.jar} went from the jar built a minute earlier to the jar
     * from the registry, while the job reported 202 and the container came up healthy. "Recreate"
     * means <i>make this container again</i>; "deploy" means <i>fetch what is new</i>. Whoever
     * wants both presses both.</p>
     *
     * <p>The consequence, and it is deliberate: a service whose image is not on this host cannot be
     * recreated. {@link #hasLocalImage} is what the caller checks first, so the refusal can name
     * deploy instead of letting compose fail with its own wording.</p>
     */
    public int recreate(String service, Consumer<String> output) throws IOException {
        assertEnvFileFresh();
        return run(recreateCommand(service), output);
    }

    /**
     * The command line {@link #recreate} runs.
     *
     * <p>Package-visible for one reason: a test can read it without a docker daemon, which is the
     * only way to hold "recreate does not pull" against something other than a reviewer's memory.
     * {@code docker compose up} can fetch too, through {@code --pull}, so the assertion is about
     * every token and not only about the subcommand.</p>
     */
    List<String> recreateCommand(String service) {
        List<String> command = base();
        command.addAll(List.of("up", "--detach", "--no-deps", "--force-recreate"));
        command.addAll(refuseSelf(List.of(service)));
        return command;
    }

    /**
     * Whether this service's image is already on this host.
     *
     * <p>The same two questions {@link #pull} asks when a pull has failed - what image does the
     * compose file name for this service, and does docker have it - asked on their own, because
     * {@link #recreate} needs the answer <b>without</b> a pull having happened.</p>
     */
    public boolean hasLocalImage(String service) {
        Optional<String> image = imageOf(service);
        return image.isPresent() && imageExistsLocally(image.get());
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

    /**
     * Every service the <b>active profile selection</b> carries, in file order, with its image.
     *
     * <p>This is deliberately not every service in the file: it is what a deployment that names
     * nothing touches, and what the interface lists. The two standbys sit in a profile no ordinary
     * selection carries, so they are absent here and that is correct - a whole-stack deploy must
     * not start a second proxy. {@link #everyService} is the other question.</p>
     */
    public Map<String, String> services() throws IOException {
        return config(false);
    }

    /**
     * Every service the compose file defines, whatever profile it sits in.
     *
     * <p><b>Why this exists (season-2-ops/122):</b> {@code docker compose config} answers for the
     * profiles that are enabled, so under {@code COMPOSE_PROFILES=db,bot,mc,backup,steward} the
     * standbys are not in the answer at all - and a question asked through {@link #services} about
     * {@code proxy-standby} comes back "no such service" rather than "no image". That made
     * {@link #hasLocalImage} false for a service whose image is certainly here, which refused every
     * recreate of a standby with exit 1 and aborted the run that wanted it.</p>
     *
     * <p>{@code --profile "*"} enables all of them for this one read. Nothing is started by it;
     * {@code config} only prints.</p>
     */
    public Map<String, String> everyService() throws IOException {
        return config(true);
    }

    private Map<String, String> config(boolean allProfiles) throws IOException {
        StringBuilder json = new StringBuilder();
        int code = run(configCommand(allProfiles), line -> json.append(line).append('\n'));
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

    /**
     * The command line {@link #config} runs.
     *
     * <p>Visible so a test can hold the {@code --profile "*"} against something other than a
     * reviewer's memory: it is one argument, it goes BEFORE the subcommand because it is a
     * top-level flag, and without it the standbys are missing from the answer entirely.</p>
     */
    List<String> configCommand(boolean allProfiles) {
        List<String> command = base();
        if (allProfiles) {
            command.addAll(List.of("--profile", "*"));
        }
        command.addAll(List.of("config", "--format", "json"));
        return command;
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
            // everyService, not services: a standby is invisible to the active profile selection,
            // and "not in this selection" is not "has no image".
            String image = everyService().get(service);
            return image == null || image.isBlank() ? Optional.empty() : Optional.of(image);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private boolean imageExistsLocally(String image) {
        try {
            return run(List.of("docker", "image", "inspect", image), line -> {}) == 0;
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
