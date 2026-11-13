package eu.nordtal.season.smp.config;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes the default wheel prizes as the pack builder reads them: a JSON list of {@code item} and {@code amount}. */
final class WheelPrizeList {

    private WheelPrizeList() {}

    /** Writes the list to the file {@code args[0]}. */
    static void main(final String[] args) throws IOException {
        final List<Map<String, Object>> prizes = DefaultSmp.WHEEL_PRIZES.stream()
                .map(prize -> {
                    final Map<String, Object> out = new LinkedHashMap<>();
                    out.put("item", prize.item());
                    out.put("amount", prize.amount());
                    return out;
                })
                .toList();
        final Path file = Path.of(args[0]);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(
                file, new GsonBuilder().setPrettyPrinting().create().toJson(prizes) + "\n", StandardCharsets.UTF_8);
    }
}
