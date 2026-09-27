package eu.nordtal.s2.steward.worker.configfile;

import java.util.List;
import java.util.Map;

/** The document, plus what only a writer needs: the lines and each scalar's span. */
record Parsed(ConfigDocument document, List<String> lines, Map<String, Span> spans) {}
