package eu.nordtal.season.stewardagent.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageDocumentsTest {

    @Test
    void theWheelDocumentIsFoundWhereTheImageKeepsIt(@TempDir final Path image) throws IOException {
        final Path smp = Files.createDirectories(image.resolve("documents/smp"));
        Files.writeString(smp.resolve("declarations.json"), """
                [{"namespace":"smp","name":"wheel","title":"Wheel of Fortune","englishOnly":false,"area":"Menus",\
                "shape":"MENU","rows":1,"values":{},"slots":[]}]""");
        Files.writeString(smp.resolve("wheel.json"), "{\"parts\":[]}");

        assertEquals(
                "wheel",
                PackBuilds.imageDocuments(image)
                        .applied("smp", "wheel")
                        .declaration()
                        .name());
    }
}
