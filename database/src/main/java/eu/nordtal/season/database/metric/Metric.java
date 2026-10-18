package eu.nordtal.season.database.metric;

/** The numbers steward samples, each under a key. */
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

    public String key() {
        return key;
    }
}
