package eu.nordtal.s2.stewardagent.plan;

import eu.nordtal.s2.common.time.NetworkTime;
import eu.nordtal.s2.stewardagent.source.Modrinth;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which Modrinth project a jar on the disk came from, told by its SHA-512.
 *
 * Hashes and projects are cached, names and icons for a day; an unreachable Modrinth only leaves a jar unnamed.
 */
final class JarIdentity {

    private static final Logger log = LoggerFactory.getLogger(JarIdentity.class);

    static final Duration PROJECT_TTL = Duration.ofDays(1);

    private record FileKey(String path, long size, long modified) {}

    private record Kept(Modrinth.Project project, Instant fetched) {}

    private final Modrinth modrinth;
    private final Supplier<Instant> clock;
    private final Map<FileKey, String> hashes = new ConcurrentHashMap<>();
    private final Map<String, Optional<String>> projectOfHash = new ConcurrentHashMap<>();
    private final Map<String, Kept> projects = new ConcurrentHashMap<>();

    JarIdentity(final Modrinth modrinth) {
        this(modrinth, NetworkTime.clock()::instant);
    }

    JarIdentity(final Modrinth modrinth, final Supplier<Instant> clock) {
        this.modrinth = modrinth;
        this.clock = clock;
    }

    /** The Modrinth project of each jar Modrinth published, keyed by file name; never throws. */
    Map<String, Modrinth.Project> identify(final List<Installation.Jar> jars) {
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

        final Map<String, Modrinth.Project> byId = projects(projectOfJar.values());
        final Map<String, Modrinth.Project> answer = new LinkedHashMap<>();
        projectOfJar.forEach((fileName, id) -> {
            final Modrinth.Project project = byId.get(id);
            if (project != null) {
                answer.put(fileName, project);
            }
        });
        return answer;
    }

    /**
     * Name and icon of each of these Modrinth projects, keyed by id, from the same day-long cache.
     *
     * Never throws: an id Modrinth could not be asked about is missing from the answer.
     */
    Map<String, Modrinth.Project> projects(final java.util.Collection<String> ids) {
        final Instant now = clock.get();
        final Set<String> stale = new LinkedHashSet<>();
        for (final String id : ids) {
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
        for (final String id : ids) {
            final Kept kept = projects.get(id);
            if (kept != null) {
                answer.put(id, kept.project());
            }
        }
        return answer;
    }

    private @Nullable String hash(final Installation.Jar jar) {
        final FileKey key;
        try {
            key = new FileKey(
                    jar.path().toString(),
                    Files.size(jar.path()),
                    Files.getLastModifiedTime(jar.path()).toMillis());
        } catch (final IOException gone) {
            return null;
        }
        return hashes.computeIfAbsent(key, ignored -> sha512(jar));
    }

    private static @Nullable String sha512(final Installation.Jar jar) {
        try (DigestInputStream in =
                new DigestInputStream(Files.newInputStream(jar.path()), MessageDigest.getInstance("SHA-512"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (final IOException | NoSuchAlgorithmException unreadable) {
            log.warn("Could not hash {}: {}", jar.path(), unreadable.getMessage());
            return null;
        }
    }
}
