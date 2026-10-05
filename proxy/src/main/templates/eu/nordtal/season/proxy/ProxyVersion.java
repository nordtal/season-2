package eu.nordtal.season.proxy;

/** The version from gradle.properties, which the {@code @Plugin} annotation of {@link ProxyPlugin} reads. */
final class ProxyVersion {

    static final String VALUE = "${version}";

    private ProxyVersion() {}
}
