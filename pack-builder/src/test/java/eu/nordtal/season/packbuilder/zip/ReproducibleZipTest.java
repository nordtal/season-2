package eu.nordtal.season.packbuilder.zip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class ReproducibleZipTest {

    @Test
    void theSameFilesGiveTheSameBytesInAnyOrderAndAnyTimeZone() {
        final Map<String, byte[]> one = new LinkedHashMap<>();
        one.put("b/two.json", bytes("{}"));
        one.put("a.txt", bytes("one"));
        final Map<String, byte[]> other = new LinkedHashMap<>();
        other.put("a.txt", bytes("one"));
        other.put("b/two.json", bytes("{}"));
        final TimeZone zone = TimeZone.getDefault();
        final byte[] first;
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            first = ReproducibleZip.write(one);
        } finally {
            TimeZone.setDefault(zone);
        }
        assertArrayEquals(first, ReproducibleZip.write(other));
    }

    @Test
    void theZipHoldsItsFilesAloneSortedAndAtOneTime() throws IOException {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("b/two.json", bytes("{}"));
        files.put("a.txt", bytes("one"));
        final List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(ReproducibleZip.write(files)))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
                assertEquals(LocalDateTime.of(1980, 2, 1, 0, 0), entry.getTimeLocal());
                assertEquals(ZipEntry.DEFLATED, entry.getMethod());
                assertArrayEquals(files.get(entry.getName()), zip.readAllBytes());
            }
        }
        assertEquals(List.of("a.txt", "b/two.json"), names);
    }

    @Test
    void theSha1IsLowerCaseHex() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", ReproducibleZip.sha1(bytes("abc")));
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
