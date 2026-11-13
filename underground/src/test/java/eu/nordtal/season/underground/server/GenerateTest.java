package eu.nordtal.season.underground.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerateTest {

    private static final String TAGS = "data/minecraft/tags/worldgen/biome/has_structure/";

    @Test
    void theDataPackTurnsOffEveryStructureButThoseOfTheCaves(@TempDir final Path directory) throws IOException {
        final Path jar = directory.resolve("server.jar");
        try (OutputStream file = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            entry(zip, "version.json", "{\"pack_version\": {\"data_major\": 121, \"data_minor\": 0}}");
            entry(zip, TAGS + "stronghold.json", "{\"values\": [\"#minecraft:is_overworld\"]}");
            entry(zip, TAGS + "mineshaft.json", "{\"values\": [\"#minecraft:is_overworld\"]}");
        }
        final Path pack = directory.resolve("pack");

        Generate.dataPack(jar, pack);

        assertEquals("{\"replace\": true, \"values\": []}\n", Files.readString(pack.resolve(TAGS + "stronghold.json")));
        assertFalse(Files.exists(pack.resolve(TAGS + "mineshaft.json")));
        assertTrue(Files.readString(pack.resolve("pack.mcmeta")).contains("\"min_format\": 121"));
        assertEquals(
                "10.0\n",
                Files.readString(
                        pack.resolve("data/minecraft/worldgen/density_function/overworld/sloped_cheese.json")));
    }

    private static void entry(final ZipOutputStream zip, final String name, final String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
