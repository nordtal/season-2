package eu.nordtal.s2.steward.ui.push;

/** Where {@link AlertWatch} reads the traffic light from, so a test can hand it a sequence it chose. */
public interface AlertLevelSource {

    /** The current reading; an unchecked exception means nothing to report this cycle. */
    AlertReading current();
}
