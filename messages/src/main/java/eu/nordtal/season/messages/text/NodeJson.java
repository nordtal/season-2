package eu.nordtal.season.messages.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts a parsed text to JSON, which the browser's web target walks instead of parsing the text again.
 * The messages README shows the shape of each node.
 */
public final class NodeJson {

    private NodeJson() {}

    public static List<Object> of(final List<Node> nodes) {
        final List<Object> json = new ArrayList<>(nodes.size());
        nodes.forEach(node -> json.add(of(node)));
        return json;
    }

    private static Object of(final Node node) {
        return switch (node) {
            case Node.Literal literal -> literal.text();
            case Node.Value value -> {
                final Map<String, Object> json = new LinkedHashMap<>();
                json.put("v", value.name());
                if (value.kind() != null) {
                    json.put("k", value.kind());
                }
                if (value.style() != null) {
                    json.put("s", value.style());
                }
                yield json;
            }
            case Node.Choice choice -> {
                final Map<String, Object> cases = new LinkedHashMap<>();
                choice.cases().forEach((name, nodes) -> cases.put(name, of(nodes)));
                final Map<String, Object> json = new LinkedHashMap<>();
                json.put("c", choice.name());
                json.put("plural", choice.plural());
                json.put("cases", cases);
                yield json;
            }
            case Node.Pound pound -> Map.of("pound", pound.name());
            case Node.Tag tag -> {
                final Map<String, Object> json = new LinkedHashMap<>();
                json.put("tag", tag.name());
                json.put("shape", tag.shape().name());
                json.put("args", tag.args().stream().map(arg -> of(arg.parts())).toList());
                yield json;
            }
        };
    }
}
