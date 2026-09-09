package eu.nordtal.s2.common.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link UpdateReport} to and from the JSON that lives in {@code update_request.result}.
 *
 * <h2>Hand-written, and that is the cheaper choice here</h2>
 * {@code :common} is compiled against no platform and declares its whole persistence stack
 * {@code compileOnly} so that a plugin taking one class does not take a megabyte. A JSON library
 * would be the first dependency in this module that exists purely to move four record types across
 * a text column - and a reflective record mapper is exactly the shape {@code Json} in the updater
 * argues against by name: an upstream field that stops arriving comes back as a silent {@code null}
 * rather than as an error. The whole grammar here is objects, arrays, strings and one enum, and the
 * writer and the reader are on the same side of the wire in the same build.
 *
 * <h2>An unreadable row is not an error</h2>
 * {@link #parse} answers empty rather than throwing. The rows this reads are written by another
 * process, possibly an older one mid-deployment, and the caller is a Discord embed or a chat line:
 * failing to draw a report is a worse outcome than drawing the raw text, which is what every caller
 * falls back to.
 */
public final class UpdateReports {

    private UpdateReports() {
    }

    // ---------------------------------------------------------------- writing

    public static String toJson(final UpdateReport report) {
        final StringBuilder out = new StringBuilder(256);
        out.append("{\"stage\":").append(quote(report.stage().name()));

        out.append(",\"services\":[");
        for (int i = 0; i < report.services().size(); i++) {
            final UpdateReport.ServiceLine line = report.services().get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"service\":").append(quote(line.service()))
                    .append(",\"state\":").append(quote(line.state().name()))
                    .append(",\"changes\":[");
            for (int c = 0; c < line.changes().size(); c++) {
                final UpdateReport.Change change = line.changes().get(c);
                if (c > 0) {
                    out.append(',');
                }
                out.append("{\"artefact\":").append(quote(change.artefact()))
                        .append(",\"from\":").append(quote(change.from()))
                        .append(",\"to\":").append(quote(change.to()));
                // Written only when it is not the default, the way `detail` is - and here that has
                // a second effect worth naming. A reader older than 2026-09-09 throws on a key it
                // does not know, and UpdateReports#parse turns that into "this is not a report",
                // which every surface draws as the raw text. Omitting the common case means a
                // network mid-deployment keeps drawing ordinary runs properly, and only a report
                // that actually carries an unsupported artefact falls back.
                if (change.state() != UpdateReport.Change.State.MOVING) {
                    out.append(",\"state\":").append(quote(change.state().name()));
                }
                out.append('}');
            }
            out.append(']');
            if (line.detail() != null) {
                out.append(",\"detail\":").append(quote(line.detail()));
            }
            out.append('}');
        }
        out.append(']');

        out.append(",\"notes\":[");
        for (int i = 0; i < report.notes().size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(quote(report.notes().get(i)));
        }
        out.append("]}");
        return out.toString();
    }

    /** {@code null} becomes the JSON literal, which is how "nothing installed" survives the trip. */
    private static String quote(final String value) {
        if (value == null) {
            return "null";
        }
        final StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    // ---------------------------------------------------------------- reading

    /**
     * @param json the {@code result} column, which may be {@code null}, empty, or the plain text a
     *             version of this software older than 2026-09-07 wrote
     * @return the report, or empty when this is not one
     */
    public static Optional<UpdateReport> parse(final String json) {
        if (json == null || json.isBlank() || json.charAt(0) != '{') {
            return Optional.empty();
        }
        try {
            return Optional.of(new Reader(json).report());
        } catch (final RuntimeException malformed) {
            return Optional.empty();
        }
    }

    /**
     * A cursor over the text, reading only the shape {@link #toJson} writes.
     *
     * <p>Not a general JSON parser and not trying to be one: it accepts what this class produces
     * and rejects everything else by throwing, which {@link #parse} turns into "this is not a
     * report".</p>
     */
    private static final class Reader {

        private final String text;
        private int at;

        Reader(final String text) {
            this.text = text;
        }

        UpdateReport report() {
            expect('{');
            UpdateReport.Stage stage = UpdateReport.Stage.PLANNED;
            final List<UpdateReport.ServiceLine> services = new ArrayList<>();
            final List<String> notes = new ArrayList<>();

            while (true) {
                final String key = string();
                expect(':');
                switch (key) {
                    case "stage" -> stage = UpdateReport.Stage.valueOf(string());
                    case "services" -> readArray(() -> services.add(serviceLine()));
                    case "notes" -> readArray(() -> notes.add(string()));
                    default -> throw new IllegalStateException("unknown key: " + key);
                }
                if (!more('}')) {
                    break;
                }
            }
            return new UpdateReport(stage, services, notes);
        }

        private UpdateReport.ServiceLine serviceLine() {
            expect('{');
            String service = null;
            UpdateReport.State state = UpdateReport.State.UNCHANGED;
            final List<UpdateReport.Change> changes = new ArrayList<>();
            String detail = null;

            while (true) {
                final String key = string();
                expect(':');
                switch (key) {
                    case "service" -> service = string();
                    case "state" -> state = UpdateReport.State.valueOf(string());
                    case "changes" -> readArray(() -> changes.add(change()));
                    case "detail" -> detail = nullableString();
                    default -> throw new IllegalStateException("unknown key: " + key);
                }
                if (!more('}')) {
                    break;
                }
            }
            return new UpdateReport.ServiceLine(service, state, changes, detail);
        }

        private UpdateReport.Change change() {
            expect('{');
            String artefact = null;
            String from = null;
            String to = null;
            UpdateReport.Change.State state = UpdateReport.Change.State.MOVING;
            while (true) {
                final String key = string();
                expect(':');
                switch (key) {
                    case "artefact" -> artefact = string();
                    case "from" -> from = nullableString();
                    case "to" -> to = string();
                    case "state" -> state = UpdateReport.Change.State.valueOf(string());
                    default -> throw new IllegalStateException("unknown key: " + key);
                }
                if (!more('}')) {
                    break;
                }
            }
            return new UpdateReport.Change(artefact, from, to, state);
        }

        /** Runs {@code element} once per array entry, and eats an empty array without calling it. */
        private void readArray(final Runnable element) {
            expect('[');
            skipSpace();
            if (peek() == ']') {
                at++;
                return;
            }
            while (true) {
                element.run();
                skipSpace();
                if (peek() == ',') {
                    at++;
                    continue;
                }
                expect(']');
                return;
            }
        }

        /** @return whether a comma followed; consumes the closing brace when it did not */
        private boolean more(final char close) {
            skipSpace();
            if (peek() == ',') {
                at++;
                return true;
            }
            expect(close);
            return false;
        }

        private String nullableString() {
            skipSpace();
            if (text.startsWith("null", at)) {
                at += 4;
                return null;
            }
            return string();
        }

        private String string() {
            skipSpace();
            expect('"');
            final StringBuilder out = new StringBuilder();
            while (true) {
                final char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                final char escaped = text.charAt(at++);
                switch (escaped) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> out.append(escaped);
                }
            }
        }

        private void expect(final char c) {
            skipSpace();
            if (text.charAt(at) != c) {
                throw new IllegalStateException("expected " + c + " at " + at);
            }
            at++;
        }

        private char peek() {
            return text.charAt(at);
        }

        private void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }
    }
}
