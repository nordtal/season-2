package eu.nordtal.s2.database.metric;

/** Every number the worker samples, by the name the table and the browser use for it. */
public enum Metric {
    LOAD1("load1"),
    CPU_PERCENT("cpu_percent"),
    MEMORY_USED_BYTES("memory_used_bytes"),
    MEMORY_TOTAL_BYTES("memory_total_bytes"),
    DISK_USED_BYTES("disk_used_bytes"),
    DISK_TOTAL_BYTES("disk_total_bytes"),
    MEMORY_BYTES("memory_bytes");

    private final String key;

    Metric(final String key) {
        this.key = key;
    }

    /** Returns the name stored in {@code metric_sample.metric} and asked for by the browser. */
    public String key() {
        return key;
    }
}
