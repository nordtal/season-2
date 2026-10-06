package eu.nordtal.season.steward.settings;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Turns what the form sends into the JSON value a setting holds, by the type its schema names.
 *
 * The form sends text: a scalar's, a list's entries, or one record per section of a list of sections.
 */
public final class SettingValues {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private SettingValues() {}

    /**
     * Returns {@code sent} as the value of the setting {@code node} describes.
     *
     * @throws BadRequestResponse when {@code sent} does not have the setting's shape, which the form always has
     * @throws RequestRefused when {@code sent} is not of the setting's type
     */
    static JsonElement convert(final String path, final JsonObject node, final JsonElement sent) {
        final String kind = node.has("kind") ? node.get("kind").getAsString() : "SCALAR";
        final boolean sections = "LIST".equals(kind)
                && node.has("children")
                && !node.getAsJsonObject("children").isEmpty();
        if (sections) {
            return sectionsOf(path, node, sent);
        }
        if ("LIST".equals(kind)) {
            if (!sent.isJsonArray()) {
                throw new BadRequestResponse(path + " is a list");
            }
            final JsonArray list = new JsonArray();
            sent.getAsJsonArray().forEach(item -> list.add(scalar(path, node, item)));
            return list;
        }
        return scalar(path, node, sent);
    }

    private static JsonArray sectionsOf(final String path, final JsonObject node, final JsonElement sent) {
        if (!sent.isJsonArray()) {
            throw new BadRequestResponse(path + " is a list of sections");
        }
        final JsonObject fields = node.getAsJsonObject("children");
        final JsonArray sections = new JsonArray();
        for (final JsonElement section : sent.getAsJsonArray()) {
            if (!section.isJsonObject()) {
                throw new BadRequestResponse(path + " holds sections, one record each");
            }
            final JsonObject converted = new JsonObject();
            for (final Map.Entry<String, JsonElement> field :
                    section.getAsJsonObject().entrySet()) {
                final JsonElement fieldNode = fields.get(field.getKey());
                if (fieldNode == null || !fieldNode.isJsonObject()) {
                    throw new BadRequestResponse(path + " has no field " + field.getKey());
                }
                converted.add(
                        field.getKey(),
                        convert(path + "." + field.getKey(), fieldNode.getAsJsonObject(), field.getValue()));
            }
            sections.add(converted);
        }
        return sections;
    }

    private static JsonPrimitive scalar(final String path, final JsonObject node, final JsonElement sent) {
        if (!sent.isJsonPrimitive()) {
            throw new BadRequestResponse(path + " is a single value");
        }
        final String text = sent.getAsString();
        final String type = node.has("type") && !node.get("type").isJsonNull()
                ? node.get("type").getAsString()
                : "STRING";
        try {
            return switch (type) {
                case "INTEGER" -> new JsonPrimitive(Long.parseLong(text.strip()));
                case "DECIMAL" -> new JsonPrimitive(new BigDecimal(text.strip()));
                case "BOOLEAN" -> new JsonPrimitive(booleanOf(text));
                default -> new JsonPrimitive(text);
            };
        } catch (final NumberFormatException notANumber) {
            throw new RequestRefused(400, ANSWER.notOfType(path, StewardTexts.Expected.valueOf(type), text));
        }
    }

    private static boolean booleanOf(final String text) {
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new NumberFormatException(text);
        };
    }

    /**
     * Refuses a list of sections that drops the section its schema protects.
     *
     * @throws RequestRefused naming the section that has to stay
     */
    static void refuseRemovingTheProtected(final String path, final JsonObject node, final JsonElement value) {
        if (!(node.get("protectedEntry") instanceof final JsonObject kept) || !value.isJsonArray()) {
            return;
        }
        final String field = kept.get("field").getAsString();
        final String wanted = kept.get("value").getAsString();
        for (final JsonElement section : value.getAsJsonArray()) {
            if (section instanceof final JsonObject object
                    && object.get(field) instanceof final JsonPrimitive held
                    && held.getAsString().equals(wanted)) {
                return;
            }
        }
        throw new RequestRefused(400, ANSWER.keepSection(path, field, wanted));
    }
}
