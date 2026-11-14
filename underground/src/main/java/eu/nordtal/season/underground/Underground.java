package eu.nordtal.season.underground;

import eu.nordtal.season.underground.check.Histogram;
import eu.nordtal.season.underground.check.Seams;
import eu.nordtal.season.underground.check.Slice;
import eu.nordtal.season.underground.server.Generate;
import eu.nordtal.season.underground.server.ScratchServer;
import eu.nordtal.season.underground.server.Upgrade;
import eu.nordtal.season.underground.server.Verify;
import eu.nordtal.season.underground.transplant.Parameters;
import eu.nordtal.season.underground.transplant.Transplant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The command line of the tool; the README of the module lists the commands in the order of a run. */
public final class Underground {

    private Underground() {}

    public static void main(final String[] words) throws IOException, InterruptedException {
        if (words.length == 0) {
            throw new IllegalArgumentException(
                    "Name a command: histogram, upgrade, generate, transplant, check, slice or verify.");
        }
        final Arguments arguments = new Arguments(List.of(Arrays.copyOfRange(words, 1, words.length)));
        switch (words[0]) {
            case "histogram" -> histogram(arguments);
            case "transplant" -> transplant(arguments);
            case "upgrade" -> Upgrade.run(setup(arguments, 0), arguments.path("map"), arguments.path("out"));
            case "generate" ->
                Generate.run(setup(arguments, seed(arguments)), arguments.path("data"), regions(arguments));
            case "verify" -> verify(arguments);
            case "check" -> check(arguments);
            case "slice" -> slice(arguments);
            default -> throw new IllegalArgumentException("Unknown command " + words[0] + ".");
        }
    }

    private static void transplant(final Arguments arguments) throws IOException {
        final Parameters standard = Parameters.standard();
        final Parameters measures = new Parameters(
                (int) arguments.number("ambiguous-reach", standard.ambiguousReach()),
                (int) arguments.number("outside-reach", standard.outsideReach()),
                (int) arguments.number("taper", standard.taper()),
                (int) arguments.number("shell", standard.shell()),
                (int) arguments.number("protected-reach", standard.protectedReach()),
                (int) arguments.number("wall", standard.wall()),
                (int) arguments.number("crust", standard.crust()),
                (int) arguments.number("band", standard.band()),
                arguments.number("roughness", standard.roughness()),
                (int) arguments.number("top", standard.top()),
                !arguments.text("entrances", "yes").equals("no"));
        measures.check();
        final Transplant transplant =
                new Transplant(arguments.path("map"), arguments.path("parallel"), arguments.path("out"), measures);
        System.out.println("transplant finished: " + transplant.run(arguments.regions(arguments.path("map"))));
    }

    /** Returns --seed, which a world generated in another seed would not match. */
    private static long seed(final Arguments arguments) {
        return arguments.whole("seed");
    }

    /** Returns the scratch server of a command; the upgrade generates nothing, so its seed does not matter. */
    private static ScratchServer.Setup setup(final Arguments arguments, final long seed) {
        if (!arguments.text("eula", "").equals("true")) {
            throw new IllegalArgumentException(
                    "No scratch server starts without the EULA. Pass --eula true to accept https://aka.ms/MinecraftEULA.");
        }
        final String java =
                arguments.text("java", ProcessHandle.current().info().command().orElse("java"));
        return new ScratchServer.Setup(
                arguments.path("work"), arguments.path("server"), java, seed, (int) arguments.number("memory", 2500));
    }

    /** Returns the regions of --regions, which a command without a map to list them from cannot do without. */
    private static List<int[]> regions(final Arguments arguments) throws IOException {
        arguments.text("regions");
        return arguments.regions(arguments.path("work"));
    }

    private static void verify(final Arguments arguments) throws IOException, InterruptedException {
        final String packs = arguments.text("datapacks", "");
        final String map = arguments.text("map", "");
        final Path world = arguments.path("world");
        require(Verify.run(
                setup(arguments, seed(arguments)),
                world,
                packs.isEmpty() ? null : Path.of(packs),
                map.isEmpty() ? null : Path.of(map),
                arguments.regions(world)));
    }

    private static void require(final boolean good) {
        if (!good) {
            System.exit(1);
        }
    }

    private static void check(final Arguments arguments) throws IOException {
        final Seams.Limits limits = new Seams.Limits(arguments.number("flat-share", 1.5));
        require(Seams.run(
                arguments.path("map"),
                arguments.path("parallel"),
                arguments.path("out"),
                arguments.regions(arguments.path("out")),
                limits));
    }

    /** Draws worlds given as --worlds label=folder,label=folder along --line x,z:x,z with any number of corners. */
    private static void slice(final Arguments arguments) throws IOException {
        final List<int[]> corners = new ArrayList<>();
        for (final String corner : arguments.text("line").split(":", -1)) {
            final String[] parts = corner.split(",", -1);
            corners.add(new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])});
        }
        if (corners.size() < 2) {
            throw new IllegalArgumentException("A line has at least two corners: --line x,z:x,z.");
        }
        final String[] heights = arguments.text("y", "-64..319").split("\\.\\.", -1);
        final Slice.Line line =
                new Slice.Line(corners, Integer.parseInt(heights[0]), Integer.parseInt(heights[1]), (int)
                        arguments.number("scale", 2));
        final List<String> labels = new ArrayList<>();
        final List<Path> worlds = new ArrayList<>();
        for (final String world : arguments.text("worlds").split(",", -1)) {
            labels.add(world.substring(0, world.indexOf('=')));
            worlds.add(Path.of(world.substring(world.indexOf('=') + 1)));
        }
        new Slice().draw(arguments.path("out"), line, labels, worlds);
    }

    private static void histogram(final Arguments arguments) throws IOException {
        final String report = Histogram.of(arguments.path("map"), arguments.regions(arguments.path("map")));
        Files.writeString(arguments.path("out"), report, StandardCharsets.UTF_8);
    }
}
