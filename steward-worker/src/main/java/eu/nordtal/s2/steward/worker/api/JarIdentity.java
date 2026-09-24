package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.steward.worker.plan.Installation;
import eu.nordtal.s2.steward.worker.source.Modrinth;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Which Modrinth project a jar on the disk came from, told by its hash.
 *
 * <p>The plugins the network gives carry no row, so their name, picture and link have to come from
 * somewhere else, and the file name is not it: {@code Chunky-Bukkit-1.5.3.jar} does not say
 * "Chunky". Modrinth answers a file's SHA-512 with the version it belongs to, so a jar Modrinth
 * published is recognised whoever put it there, and one it never published - the Nordtal jars,
 * display-tags from GitHub - is recognised as not being one.
 *
 * <p><b>Nothing here may slow the list down or break it.</b> A hash is computed once per file and
 * size, a hash's project is asked once and kept (a published file never changes owner), and a
 * project's name and icon are kept for a day. When Modrinth cannot be reached, the jar is simply not
 * identified this time and is asked about again on the next call.
 */
final class JarIdentity {

    private static final Logger log = LoggerFactory.getLogger(JarIdentity.class);

    static final Duration PROJECT_TTL = Duration.ofDays(1);

    private record FileKey(String path, long size, long modified) {
    }

    private record Kept(Modrinth.Project project, Instant fetched) {
    }

    private final Modrinth modrinth;
    private final Supplier<Instant> clock;
    private final Map<FileKey, String> hashes = new ConcurrentHashMap<>();
    private final Map<String, Optional<String>> projectOfHash = new ConcurrentHashMap<>();
    private final Map<String, Kept> projects = new ConcurrentHashMap<>();

    JarIdentity(final @NotNull Modrinth modrinth) {
        this(modrinth, Instant::now);
    }

    JarIdentity(final @NotNull Modrinth modrinth, final @NotNull Supplier<Instant> clock) {
        this.modrinth = modrinth;
        this.clock = clock;
    }

    /** The Modrinth project of each jar Modrinth published, keyed by file name. Never throws. */
    @NotNull Map<String, Modrinth.Project> identify(final @NotNull List<Installation.Jar> jars) {
        final Map<String, String> projectOfJar = new LinkedHashMap<>();
        for (final Installation.Jar jar : jars) {
            final String hash = hash(jar);
            if (hash == null) {
                continue;
            }
            Optional<String> project = projectOfHash.get(hash);
            if (project == null) {
                try {
                    project = Optional.ofNullable(modrinth.projectOfFile(hash));
                    projectOfHash.put(hash, project);
                } catch (final IOException unreachable) {
                    log.warn("Could not ask Modrinth about {}: {}", jar.fileName(), unreachable.getMessage());
                    continue;
                }
            }
            project.ifPresent(id -> projectOfJar.put(jar.fileName(), id));
        }

        final Instant now = clock.get();
        final Set<String> stale = new LinkedHashSet<>();
        for (final String id : projectOfJar.values()) {
            final Kept kept = projects.get(id);
            if (kept == null || kept.fetched().plus(PROJECT_TTL).isBefore(now)) {
                stale.add(id);
            }
        }
        if (!stale.isEmpty()) {
            try {
                for (final Modrinth.Project project : modrinth.projects(stale)) {
                    projects.put(project.projectId(), new Kept(project, now));
                }
            } catch (final IOException unreachable) {
                // A name that is a day old is still the name; only what was never fetched is lost.
                log.warn("Could not read {} Modrinth project(s): {}", stale.size(), unreachable.getMessage());
            }
        }

        final Map<String, Modrinth.Project> answer = new LinkedHashMap<>();
        projectOfJar.forEach((fileName, id) -> {
            final Kept kept = projects.get(id);
            if (kept != null) {
                answer.put(fileName, kept.project());
            }
        });
        return answer;
    }

    private String hash(final Installation.Jar jar) {
        final FileKey key;
        try {
            key = new FileKey(jar.path().toString(), Files.size(jar.path()),
                    Files.getLastModifiedTime(jar.path()).toMillis());
        } catch (final IOException gone) {
            return null;
        }
        return hashes.computeIfAbsent(key, ignored -> sha512(jar));
    }

    private static String sha512(final Installation.Jar jar) {
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(jar.path()),
                MessageDigest.getInstance("SHA-512"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (final IOException | NoSuchAlgorithmException unreadable) {
            log.warn("Could not hash {}: {}", jar.path(), unreadable.getMessage());
            return null;
        }
    }
}
