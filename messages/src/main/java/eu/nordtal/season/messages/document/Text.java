package eu.nordtal.season.messages.document;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import eu.nordtal.season.common.language.Locales;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

/** A text of a document in each language: one text, or versions of which one shows at random.
 * In JSON a language maps to a string or to a list of strings.
 */
@JsonAdapter(Text.Adapter.class)
public record Text(Map<String, List<String>> languages) {

    public Text {
        final Map<String, List<String>> copied = new TreeMap<>();
        languages.forEach((language, versions) -> copied.put(language, List.copyOf(versions)));
        languages = Collections.unmodifiableMap(copied);
    }

    public static Text of(final String english) {
        return new Text(Map.of(Locales.DEFAULT_TAG, List.of(english)));
    }

    /** Returns the versions in {@code language}, else in English. */
    public List<String> versions(final String language) {
        return languages.getOrDefault(language, languages.getOrDefault(Locales.DEFAULT_TAG, List.of()));
    }

    /** Returns one version in {@code language}, else in English. */
    public String pick(final String language, final RandomGenerator random) {
        final List<String> versions = versions(language);
        return versions.isEmpty() ? "" : versions.get(random.nextInt(versions.size()));
    }

    static final class Adapter extends TypeAdapter<Text> {

        @Override
        public void write(final JsonWriter out, final Text text) throws IOException {
            out.beginObject();
            for (final Map.Entry<String, List<String>> language :
                    text.languages().entrySet()) {
                out.name(language.getKey());
                if (language.getValue().size() == 1) {
                    out.value(language.getValue().getFirst());
                } else {
                    out.beginArray();
                    for (final String version : language.getValue()) {
                        out.value(version);
                    }
                    out.endArray();
                }
            }
            out.endObject();
        }

        @Override
        public Text read(final JsonReader in) throws IOException {
            final Map<String, List<String>> languages = new TreeMap<>();
            in.beginObject();
            while (in.hasNext()) {
                final String language = in.nextName();
                final List<String> versions = new ArrayList<>();
                if (in.peek() == JsonToken.BEGIN_ARRAY) {
                    in.beginArray();
                    while (in.hasNext()) {
                        versions.add(in.nextString());
                    }
                    in.endArray();
                } else {
                    versions.add(in.nextString());
                }
                languages.put(language, versions);
            }
            in.endObject();
            return new Text(languages);
        }
    }
}
