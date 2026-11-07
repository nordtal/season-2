package eu.nordtal.season.steward;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.stewardagent.FakeHost;
import eu.nordtal.season.stewardagent.docker.Resident;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The live stack's containers, host and backups as the preview stands them in, from one table of figures.
 *
 * Every figure is relative to now, so "Up 4 hours" stays true however long the preview runs.
 */
final class LiveStack {

    /**
     * The containers, one per line.
     *
     * The columns are service, image, health, started ago, ended ago, exit code, memory in MiB, CPU share, the log's
     * kind and the volume's size in MiB.
     *
     * A line with no health is a stopped container, or one without a health check.
     */
    private static final String CONTAINERS = """
            caddy         | ghcr.io/nordtal/caddy:3f8f98ea7eba      | healthy | P18DT12H | -     | 0   |   52 | 0.09 | caddy    |  2
            discord-bot   | ghcr.io/nordtal/discord-bot:0.80.0      | healthy | PT4H10M  | -     | 0   |  244 | 0.11 | jvm      |  4
            hunger-games  | ghcr.io/nordtal/minecraft:0.80.0        | exited  | P4D      | PT46H | 143 |    0 | 0.00 | minecraft| 40
            limbo         | ghcr.io/nordtal/minecraft:0.80.0        | healthy | PT4H10M  | -     | 0   |  988 | 2.09 | minecraft| 24
            limbo-standby | ghcr.io/nordtal/minecraft:0.80.0        | exited  | P2D      | PT4H  | 143 |    0 | 0.00 | minecraft|  8
            migrate       | ghcr.io/nordtal/steward-agent:0.80.0    | exited  | PT4H12M  | PT4H  | 0   |    0 | 0.00 | migrate  |  1
            postgres      | postgres:17-alpine                      | healthy | PT18H30M | -     | 0   |  286 | 0.00 | postgres | 64
            proxy         | ghcr.io/nordtal/minecraft:0.80.0        | healthy | PT4H10M  | -     | 0   |  768 | 1.18 | proxy    | 12
            proxy-standby | ghcr.io/nordtal/minecraft:0.80.0        | exited  | P2D      | PT4H  | 143 |    0 | 0.00 | proxy    |  6
            smp           | ghcr.io/nordtal/minecraft:0.80.0        | healthy | PT4H10M  | -     | 0   | 2885 | 2.86 | minecraft| 96
            steward       | ghcr.io/nordtal/steward:0.80.0          | healthy | PT4H10M  | -     | 0   |  577 | 2.00 | jvm      |  4
            steward-agent | ghcr.io/nordtal/steward-agent:0.80.0    | healthy | PT4H10M  | -     | 0   |  505 | 7.63 | jvm      |  4
            steward-bunq  | ghcr.io/nordtal/steward-bunq:0.80.0     | healthy | PT4H10M  | -     | 0   |  222 | 0.02 | jvm      |  2
            """;

    /**
     * The services as compose.yml defines them, minus their images: the labels the network drawing and the pages read.
     *
     * Each backed-up server mounts two directories, which the agent also mounts below its backup sources.
     */
    private static final JsonObject DEFINITIONS = JsonParser.parseString("""
            {"caddy": {"restart": "unless-stopped", "labels": {"eu.nordtal.renew": "after",
               "eu.nordtal.section": "Entry", "eu.nordtal.entry": "true", "eu.nordtal.reaches": "steward"}},
             "discord-bot": {"restart": "unless-stopped", "labels": {"eu.nordtal.renew": "run",
               "eu.nordtal.section": "Discord", "eu.nordtal.stores-in": "postgres",
               "eu.nordtal.secrets": "NORDTAL_BOT_TOKEN"}},
             "hunger-games": {"restart": "unless-stopped", "labels": {"eu.nordtal.server": "paper",
               "eu.nordtal.plugins": "hunger-games voicechat?", "eu.nordtal.renew": "run",
               "eu.nordtal.console": "true", "eu.nordtal.backup": "stop", "eu.nordtal.section": "Paper",
               "eu.nordtal.stores-in": "postgres"}},
             "limbo": {"restart": "unless-stopped", "labels": {"eu.nordtal.server": "paper",
               "eu.nordtal.plugins": "limbo", "eu.nordtal.renew": "run", "eu.nordtal.console": "true",
               "eu.nordtal.section": "Paper", "eu.nordtal.stores-in": "postgres"}},
             "limbo-standby": {"restart": "unless-stopped", "labels": {"eu.nordtal.standby-of": "limbo"}},
             "migrate": {"restart": "no"},
             "postgres": {"restart": "unless-stopped", "labels": {"eu.nordtal.renew": "last",
               "eu.nordtal.section": "Database"}},
             "proxy": {"restart": "unless-stopped", "labels": {"eu.nordtal.server": "velocity",
               "eu.nordtal.plugins": "proxy voicechat-velocity?", "eu.nordtal.renew": "run",
               "eu.nordtal.console": "true", "eu.nordtal.section": "Entry", "eu.nordtal.entry": "true",
               "eu.nordtal.reaches": "smp hunger-games limbo", "eu.nordtal.stores-in": "postgres"}},
             "proxy-standby": {"restart": "unless-stopped", "labels": {"eu.nordtal.standby-of": "proxy"}},
             "smp": {"restart": "unless-stopped", "labels": {"eu.nordtal.server": "paper",
               "eu.nordtal.plugins": "smp packetevents=packetevents-spigot voicechat? prism=prism-paper? nbtapi=item-nbt-api-plugin?",
               "eu.nordtal.renew": "run", "eu.nordtal.console": "true", "eu.nordtal.backup": "stop",
               "eu.nordtal.section": "Paper", "eu.nordtal.stores-in": "postgres"}},
             "steward": {"restart": "unless-stopped", "labels": {"eu.nordtal.renew": "run",
               "eu.nordtal.section": "Steward", "eu.nordtal.reaches": "steward-agent steward-bunq",
               "eu.nordtal.stores-in": "postgres",
               "eu.nordtal.secrets": "NORDTAL_STEWARD_WEB_DISCORD_CLIENT_SECRET NORDTAL_STEWARD_WEB_WEB_PUSH_PRIVATE_KEY NORDTAL_STEWARD_WEB_TOTP_KEY"}},
             "steward-agent": {"restart": "unless-stopped", "labels": {"eu.nordtal.section": "Steward"}},
             "steward-bunq": {"restart": "unless-stopped", "labels": {"eu.nordtal.renew": "run",
               "eu.nordtal.section": "Steward"}}}
            """).getAsJsonObject();

    /** The volumes a backup saves, by the server that mounts each. */
    private static final Map<String, List<String>> BACKED_UP = Map.of(
            "smp",
            List.of("mc-smp", "mc-smp-plugins"),
            "hunger-games",
            List.of("mc-hunger-games", "mc-hunger-games-plugins"));

    /** The memory limit Docker reports on every container, the whole host's. */
    static final long HOST_MEMORY = 25_199_894_528L;

    private static final long GB = 1_000_000_000L;

    private static final long MIB = 1024 * 1024;

    /** The host the live overview shows: 6 cores at 12 %, 10.5 of 25.2 GB memory, 135.1 of 207.9 GB disk. */
    static final FakeHost.Spec HOST =
            new FakeHost.Spec(6, 12.0, 0.72, HOST_MEMORY, 10_500_000_000L, 2079 * GB / 10, 1351 * GB / 10);

    private static final Map<String, List<String>> LOGS = Map.of(
            "minecraft",
                    List.of(
                            "[02:11:04 INFO]: Done (14.213s)! For help, type \"help\"",
                            "[02:11:05 INFO]: [smp] Enabling smp v0.80.0",
                            "[02:11:09 INFO]: Running delayed init tasks",
                            "[02:14:31 INFO]: hmtill joined the game",
                            "[02:15:12 INFO]: ohEffi joined the game",
                            "[02:19:48 INFO]: Saving the game (this may take a moment!)",
                            "[02:19:48 INFO]: Saved the game",
                            "[02:24:02 INFO]: There are 2 of a max of 40 players online: hmtill, ohEffi"),
            "proxy",
                    List.of(
                            "[02:11:02 INFO]: Booting up Velocity 4.0.0-SNAPSHOT...",
                            "[02:11:03 INFO]: Loaded plugin proxy 0.80.0 by Nordtal",
                            "[02:11:03 INFO]: Listening on /0.0.0.0:25565",
                            "[02:14:31 INFO]: [connected player] hmtill (/172.20.0.5:51842) has connected",
                            "[02:14:32 INFO]: [server connection] hmtill -> smp has connected",
                            "[02:15:12 INFO]: [connected player] ohEffi (/172.20.0.5:51907) has connected"),
            "jvm",
                    List.of(
                            "INFO  Started in 3.412 seconds",
                            "INFO  Connected to the database",
                            "INFO  Healthy: the readiness file is fresh",
                            "INFO  Next scheduled reading in 30 seconds"),
            "caddy",
                    List.of(
                            "{\"level\":\"info\",\"msg\":\"serving initial configuration\"}",
                            "{\"level\":\"info\",\"logger\":\"http.log.access\",\"msg\":\"handled request\",\"status\":200}",
                            "{\"level\":\"info\",\"logger\":\"tls\",\"msg\":\"certificate is still valid\"}"),
            "postgres",
                    List.of(
                            "LOG:  database system is ready to accept connections",
                            "LOG:  checkpoint starting: time",
                            "LOG:  checkpoint complete: wrote 312 buffers (1.9%)"),
            "migrate", List.of("INFO  Schema is up to date", "INFO  Migrated 0 migrations"));

    private LiveStack() {}

    /** One line of {@link #CONTAINERS}. */
    private record Row(String service, Resident resident, long volumeMib) {}

    private static List<Row> rows() {
        final List<Row> rows = new ArrayList<>();
        for (final String line : CONTAINERS.strip().split("\n", -1)) {
            final String[] cell = line.split("\\|", -1);
            for (int i = 0; i < cell.length; i++) {
                cell[i] = cell[i].strip();
            }
            final boolean running = !cell[2].equals("exited");
            rows.add(new Row(
                    cell[0],
                    new Resident(
                            cell[0],
                            cell[1],
                            running,
                            running ? cell[2] : "",
                            Duration.parse(cell[3]),
                            cell[4].equals("-") ? Duration.ZERO : Duration.parse(cell[4]),
                            Integer.parseInt(cell[5]),
                            Long.parseLong(cell[6]) * MIB,
                            Double.parseDouble(cell[7]),
                            LOGS.get(cell[8])),
                    Long.parseLong(cell[9])));
        }
        return rows;
    }

    /** The containers the stand-in daemon has. */
    static List<Resident> residents() {
        return rows().stream().map(Row::resident).toList();
    }

    /**
     * The services of compose.yml: {@link #DEFINITIONS} with the images the containers run.
     *
     * @param sources where the agent mounts the volumes a backup saves
     */
    static JsonObject services(final Path sources) {
        final JsonObject services = DEFINITIONS.deepCopy();
        for (final Row row : rows()) {
            services.getAsJsonObject(row.service())
                    .addProperty("image", row.resident().image());
        }
        final com.google.gson.JsonArray agentMounts = new com.google.gson.JsonArray();
        BACKED_UP.forEach((service, volumes) -> {
            final String[] targets = {"/data", "/data/plugins"};
            final com.google.gson.JsonArray own = new com.google.gson.JsonArray();
            for (int i = 0; i < volumes.size(); i++) {
                final String source = "/srv/" + volumes.get(i);
                own.add(mount(source, targets[i]));
                agentMounts.add(mount(source, sources + "/nordtal-s2_" + volumes.get(i)));
            }
            services.getAsJsonObject(service).add("volumes", own);
        });
        services.getAsJsonObject("steward-agent").add("volumes", agentMounts);
        return services;
    }

    private static JsonObject mount(final String source, final String target) {
        final JsonObject mount = new JsonObject();
        mount.addProperty("type", "bind");
        mount.addProperty("source", source);
        mount.addProperty("target", target);
        return mount;
    }

    /** Fills each service's volume with the bytes the table says, so the disk figure of its page is not empty. */
    static void fillVolumes(final Path volumes) throws IOException {
        for (final Row row : rows()) {
            final Path data = volumes.resolve(row.service()).resolve("data.bin");
            Files.createDirectories(data.getParent());
            try (java.io.OutputStream out = Files.newOutputStream(data)) {
                final byte[] block = new byte[(int) MIB];
                for (long i = 0; i < row.volumeMib(); i++) {
                    out.write(block);
                }
            }
        }
    }

    /**
     * One run's archives: a dump and the saved volumes, as the last run on live left them.
     *
     * Each is a name, a size and how long after the dump the file was last written.
     */
    private record Run(long dump, long smp, long smpPlugins, long hungerGames, long hungerGamesPlugins) {}

    private static final Run NEWEST = new Run(26_892_558L, 4_561_760_232L, 48_920_694L, 379_662_880L, 5_055_659L);

    private static final Run PREVIOUS = new Run(26_866_514L, 4_482_615_688L, 54_621_438L, 379_662_626L, 5_040_160L);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /**
     * Writes sparse archives of the newest run, the one before it and a daily run back to two weeks ago.
     *
     * The world grows by a fraction every day, so older sets are smaller. None is copied offsite.
     */
    static void writeBackups(final Path backups, final Instant now) throws IOException {
        final Instant newest = now.minus(Duration.ofHours(7).plusMinutes(15));
        final Instant previous = newest.minus(Duration.ofHours(15).plusMinutes(30));
        write(backups, newest, NEWEST, 1.0);
        for (int day = 0; day < 14; day++) {
            write(backups, previous.minus(Duration.ofDays(day)), PREVIOUS, Math.pow(0.985, day));
        }
    }

    private static void write(final Path backups, final Instant dump, final Run run, final double scale)
            throws IOException {
        sparse(
                backups.resolve("nordtal-" + STAMP.format(dump) + ".dump"),
                (long) (run.dump() * scale),
                dump.plusSeconds(6));
        archive(backups, "s2_mc-smp", dump.plusSeconds(74), 10, run.smp(), scale);
        archive(backups, "s2_mc-smp-plugins", dump.plusSeconds(87), 0, run.smpPlugins(), 1.0);
        archive(backups, "s2_mc-hunger-games", dump.plusSeconds(87), 1, run.hungerGames(), 1.0);
        archive(backups, "s2_mc-hunger-games-plugins", dump.plusSeconds(88), 0, run.hungerGamesPlugins(), 1.0);
    }

    private static void archive(
            final Path backups,
            final String volume,
            final Instant named,
            final int takes,
            final long bytes,
            final double scale)
            throws IOException {
        sparse(
                backups.resolve("nordtal-" + volume + "-" + STAMP.format(named) + ".tar.zst"),
                (long) (bytes * scale),
                named.plusSeconds(takes));
    }

    private static void sparse(final Path file, final long bytes, final Instant modified) throws IOException {
        try (RandomAccessFile archive = new RandomAccessFile(file.toFile(), "rw")) {
            archive.setLength(bytes);
        }
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(modified));
    }
}
